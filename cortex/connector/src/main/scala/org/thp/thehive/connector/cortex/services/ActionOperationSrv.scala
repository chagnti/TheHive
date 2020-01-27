package org.thp.thehive.connector.cortex.services

import java.util.Date

import scala.util.{Failure, Try}

import play.api.Logger

import gremlin.scala.Graph
import javax.inject.Inject
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.Entity
import org.thp.scalligraph.steps.StepsOps._
import org.thp.thehive.connector.cortex.models._
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.InputTask
import org.thp.thehive.models._
import org.thp.thehive.services._

class ActionOperationSrv @Inject() (
    caseSrv: CaseSrv,
    observableSrv: ObservableSrv,
    taskSrv: TaskSrv,
    alertSrv: AlertSrv,
    logSrv: LogSrv,
    organisationSrv: OrganisationSrv,
    observableTypeSrv: ObservableTypeSrv,
    userSrv: UserSrv,
    shareSrv: ShareSrv
) {
  private[ActionOperationSrv] lazy val logger = Logger(getClass)

  /**
    * Executes an operation from Cortex responder
    * report
    * @param entity the entity concerned by the operation
    * @param operation the operation to execute
    * @param relatedCase the related case if applicable
    * @param graph graph traversal
    * @param authContext auth for access check
    * @return
    */
  def execute(entity: Entity, operation: ActionOperation, relatedCase: Option[Case with Entity], relatedTask: Option[Task with Entity])(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[ActionOperationStatus] = {

    def updateOperation(operation: ActionOperation) = ActionOperationStatus(operation, success = true, "Success")

    operation match {
      case AddTagToCase(tag) =>
        for {
          c <- Try(relatedCase.get)
          _ <- caseSrv.addTags(c, Set(tag))
        } yield updateOperation(operation)

      case AddTagToArtifact(tag) =>
        for {
          obs <- observableSrv.getOrFail(entity._id)
          _   <- observableSrv.addTags(obs, Set(tag))
        } yield updateOperation(operation)

      case CreateTask(title, description) =>
        for {
          case0        <- Try(relatedCase.get)
          createdTask  <- taskSrv.create(InputTask(title = title, description = Some(description)).toTask, None)
          organisation <- organisationSrv.getOrFail(authContext.organisation)
          _            <- shareSrv.shareTask(createdTask, case0, organisation)
        } yield updateOperation(operation)

      case AddCustomFields(name, _, value) =>
        for {
          c <- Try(relatedCase.get)
          _ <- caseSrv.setOrCreateCustomField(c, name, Some(value))
        } yield updateOperation(operation)

      case CloseTask() =>
        for {
          t <- Try(relatedTask.get)
          _ <- taskSrv.get(t).update("status" -> TaskStatus.Completed)
        } yield updateOperation(operation)

      case MarkAlertAsRead() =>
        entity match {
          case a: Alert => alertSrv.markAsRead(a._id).map(_ => updateOperation(operation))
          case x        => Failure(new Exception(s"Wrong entity for MarkAlertAsRead: ${x.getClass}"))
        }

      case AddLogToTask(content, _) =>
        for {
          t <- Try(relatedTask.get)
          _ <- logSrv.create(Log(content, new Date(), deleted = false), t)
        } yield updateOperation(operation)

      case AddArtifactToCase(_, dataType, dataMessage) =>
        for {
          c       <- Try(relatedCase.get)
          obsType <- observableTypeSrv.getOrFail(dataType)
          richObservable <- observableSrv.create(
            Observable(Some(dataMessage), 2, ioc = false, sighted = false),
            obsType,
            dataMessage,
            Set[String](),
            Nil
          )
          _ <- caseSrv.addObservable(c, richObservable)
        } yield updateOperation(operation)

      case AssignCase(owner) =>
        for {
          c <- Try(relatedCase.get)
          u <- userSrv.get(owner).getOrFail()
          _ <- Try(caseSrv.initSteps.get(c._id).unassign())
          _ <- caseSrv.assign(c, u)
        } yield updateOperation(operation)

      case AddTagToAlert(tag) =>
        entity match {
          case a: Alert => alertSrv.addTags(a, Set(tag)).map(_ => updateOperation(operation))
          case x        => Failure(new Exception(s"Wrong entity for AddTagToAlert: ${x.getClass}"))
        }

      case x =>
        val m = s"ActionOperation ${x.toString} unknown"
        logger.error(m)
        Failure(new Exception(m))
    }
  } recover {
    case e =>
      logger.error("Operation execution fails", e)
      ActionOperationStatus(operation, success = false, e.getMessage)
  }
}
