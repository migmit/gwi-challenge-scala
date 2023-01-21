package models

import play.api.libs.json.Writes
import play.api.mvc.PathBindable

final case class TaskId(id: String)
object TaskId {
  implicit val taskIdBinder: PathBindable[TaskId] =
    implicitly[PathBindable[String]].transform[TaskId](TaskId(_), _.id)
  implicit val taskIdWrites: Writes[TaskId] =
    implicitly[Writes[String]].contramap(_.id)
}
