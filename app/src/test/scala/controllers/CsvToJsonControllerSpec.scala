package controllers

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import conversion.FileDestination
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.Json
import io.circe.parser._
import models.TaskId
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pool.WorkerPool
import pool.interface.TaskCurrentState
import pool.interface.TaskFinishReason
import pool.interface.TaskInfo
import pool.interface.TaskShortInfo
import pool.interface.TaskState

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshaller._
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString

import java.nio.file.Paths
import scala.concurrent.Future

class CsvToJsonControllerSpec
    extends AnyWordSpec
    with Matchers
    with ScalatestRouteTest
    with ScalaFutures
    with FailFastCirceSupport {
  val testKit: ActorTestKit = ActorTestKit()
  implicit val actorSystem: ActorSystem[_] = testKit.internalSystem
  override protected def afterAll(): Unit = testKit.shutdownTestKit()

  val config: Config =
    ConfigFactory.parseString("csvToJson.pollingPeriodMillis = 1")
  val log: Logger = LoggerFactory.getLogger("CsvToJsonControllerSpec")
  def pool(success: Boolean = true): pool =
    new pool(success)
  class pool(success: Boolean = true)
      extends WorkerPool[TaskId, Source[ByteString, _], FileDestination] {
    val startTime = System.currentTimeMillis
    var getTaskCallCount = 0
    def createTask(
        source: => Source[ByteString, _]
    ): Future[Option[TaskInfo[TaskId, FileDestination]]] =
      Future.successful(
        Some(
          TaskInfo[TaskId, FileDestination](
            TaskId("CreatedTaskId"),
            0,
            0,
            TaskCurrentState.Scheduled()
          )
        ).filter(_ => success)
      )
    def listTasks: Future[Seq[TaskShortInfo[TaskId]]] =
      Future.successful(
        List(
          TaskShortInfo(TaskId("TaskA"), TaskState.RUNNING),
          TaskShortInfo(TaskId("TaskB"), TaskState.FAILED),
          TaskShortInfo(TaskId("TaskC"), TaskState.CANCELLED),
          TaskShortInfo(TaskId("TaskD"), TaskState.DONE)
        )
      )
    def getTask(
        taskId: TaskId
    ): Future[Option[TaskInfo[TaskId, FileDestination]]] = if (
      List("TaskA", "TaskB", "TaskC", "TaskD").contains(taskId.id)
    ) {
      getTaskCallCount = getTaskCallCount + 1
      Future.successful(
        Some(
          if (getTaskCallCount < 3)
            TaskInfo(
              taskId,
              getTaskCallCount,
              startTime,
              TaskCurrentState.Running()
            )
          else
            TaskInfo(
              taskId,
              getTaskCallCount,
              startTime,
              TaskCurrentState.Finished(
                System.currentTimeMillis(),
                new FileDestination(log, Paths.get("fake"), taskId),
                TaskFinishReason.Done
              )
            )
        )
      )
    } else Future.successful(None)
    def cancelTask(taskId: TaskId): Future[Option[Long]] =
      Future.successful(
        Some(0L).filter(_ =>
          List("TaskA", "TaskB", "TaskC", "TaskD").contains(taskId.id)
        )
      )
    def cancelAll(): Future[Unit] = Future.successful(())
  }

  "CsvToJsonController" should {
    "create task" in {
      val controller = new CsvToJsonController(config, log, pool())
      Post() ~> controller.createTask(Uri("http://example.com/")) ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "CreatedTaskId"
      }
    }
    "report failure to create task" in {
      val controller = new CsvToJsonController(config, log, pool(false))
      Post() ~> controller.createTask(Uri("http://fail.com/")) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
    "list tasks" in {
      val controller = new CsvToJsonController(config, log, pool())
      Get() ~> controller.listTasks(_.id) ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`application/json`
        responseAs[Json] shouldBe Json.obj(
          "TaskA" -> Json.obj("state" -> Json.fromString("RUNNING")),
          "TaskB" -> Json.obj("state" -> Json.fromString("FAILED")),
          "TaskC" -> Json.obj("state" -> Json.fromString("CANCELLED")),
          "TaskD" -> Json.obj(
            "state" -> Json.fromString("DONE"),
            "result" -> Json.fromString("TaskD")
          )
        )
      }
    }
    "get task details" in {
      val controller = new CsvToJsonController(config, log, pool())
      Get() ~> controller.taskDetails(TaskId("TaskA"), _.id) ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        whenReady(responseEntity.dataBytes.runWith(Sink.collection))(
          _.map(_.utf8String)
            .flatMap(parse(_).getOrElse(Json.Null).asObject)
            .map(j =>
              (
                j("linesProcessed").flatMap(_.as[Int].toOption),
                j("state").flatMap(_.as[String].toOption),
                j("result").flatMap(_.as[String].toOption)
              )
            )
            shouldBe List(
              (Some(1), Some("RUNNING"), None),
              (Some(2), Some("RUNNING"), None),
              (Some(3), Some("DONE"), Some("TaskA"))
            )
        )
      }
    }
    "report failure to get missing task details" in {
      val controller = new CsvToJsonController(config, log, pool())
      Get() ~> controller.taskDetails(TaskId("FakeTask"), _.id) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
    "cancel existing task" in {
      val controller = new CsvToJsonController(config, log, pool())
      Delete() ~> controller.cancelTask(TaskId("TaskA")) ~> check {
        status shouldBe StatusCodes.OK
      }
    }
    "report cancelling finished task" in {
      val controller = new CsvToJsonController(config, log, pool())
      Delete() ~> controller.cancelTask(TaskId("TaskD")) ~> check {
        status shouldBe StatusCodes.OK
      }
    }
    "report failure to cancel non-existent task" in {
      val controller = new CsvToJsonController(config, log, pool())
      Delete() ~> controller.cancelTask(TaskId("FakeTask")) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }
}
