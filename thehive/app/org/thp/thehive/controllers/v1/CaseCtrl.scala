package org.thp.thehive.controllers.v1

import scala.util.{Success, Try}

import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.controllers.{EntryPoint, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperty, Query}
import org.thp.scalligraph.steps.PagedResult
import org.thp.scalligraph.steps.StepsOps._
import org.thp.scalligraph.{RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.dto.v1.{InputCase, InputTask}
import org.thp.thehive.models.{Permissions, RichCase, User}
import org.thp.thehive.services._

@Singleton
class CaseCtrl @Inject() (
    entryPoint: EntryPoint,
    db: Database,
    properties: Properties,
    caseSrv: CaseSrv,
    caseTemplateSrv: CaseTemplateSrv,
    taskSrv: TaskSrv,
    userSrv: UserSrv,
    tagSrv: TagSrv,
    organisationSrv: OrganisationSrv
) extends QueryableCtrl {

  override val entityName: String                           = "case"
  override val publicProperties: List[PublicProperty[_, _]] = properties.`case` ::: metaProperties[CaseSteps]
  override val initialQuery: Query =
    Query.init[CaseSteps]("listCase", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).cases)
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, CaseSteps](
    "getCase",
    FieldsParser[IdOrName],
    (param, graph, authContext) => caseSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, CaseSteps, PagedResult[RichCase]](
    "page",
    FieldsParser[OutputParam], {
      case (OutputParam(from, to, withStats), caseSteps, authContext) =>
        caseSteps
          .richPage(from, to, withTotal = true) { c =>
            c.richCase(authContext)
//            case c if withStats =>
////              c.richCaseWithCustomRenderer(caseStatsRenderer(authContext, db, caseSteps.graph))
//              c.richCase.map(_ -> JsObject.empty) // TODO add stats
//            case c =>
//              c.richCase.map(_ -> JsObject.empty)
          }
    }
  )
  override val outputQuery: Query = Query.output[RichCase]()
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[CaseSteps, TaskSteps]("tasks", (caseSteps, authContext) => caseSteps.tasks(authContext)),
    Query[CaseSteps, List[RichCase]]("toList", (caseSteps, authContext) => caseSteps.richCase(authContext).toList)
  )

  def create: Action[AnyContent] =
    entryPoint("create case")
      .extract("case", FieldsParser[InputCase])
      .extract("caseTemplate", FieldsParser[String].optional.on("caseTemplate"))
      .extract("tasks", FieldsParser[InputTask].sequence.on("tasks"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplateName: Option[String] = request.body("caseTemplate")
        val inputCase: InputCase             = request.body("case")
        val inputTasks: Seq[InputTask]       = request.body("tasks")
        for {
          caseTemplate <- caseTemplateName.map(caseTemplateSrv.get(_).visible.richCaseTemplate.getOrFail()).flip
          customFields = inputCase.customFieldValue.map(cf => cf.name -> cf.value).toMap
          organisation <- userSrv.current.organisations(Permissions.manageCase).get(request.organisation).getOrFail()
          user         <- inputCase.user.fold[Try[Option[User with Entity]]](Success(None))(u => userSrv.getOrFail(u).map(Some.apply))
          tags         <- inputCase.tags.toTry(tagSrv.getOrCreate)
          richCase <- caseSrv.create(
            caseTemplate.fold(inputCase)(inputCase.withCaseTemplate).toCase,
            user,
            organisation,
            tags.toSet,
            customFields,
            caseTemplate,
            inputTasks.map(_.toTask -> None)
          )
        } yield Results.Created(richCase.toJson)
      }

  def get(caseIdOrNumber: String): Action[AnyContent] =
    entryPoint("get case")
      .authRoTransaction(db) { implicit request => implicit graph =>
        caseSrv
          .get(caseIdOrNumber)
          .visible
          .richCase
          .getOrFail()
          .map(richCase => Results.Ok(richCase.toJson))
      }

//  def list: Action[AnyContent] =
//    entryPoint("list case")
//      .authRoTransaction(db) { implicit request ⇒ implicit graph ⇒
//        val cases = userSrv.current.organisations.cases.richCase
//          .map(_.toJson)
//          .toList
//        Success(Results.Ok(Json.toJson(cases)))
//      }

  def update(caseIdOrNumber: String): Action[AnyContent] =
    entryPoint("update case")
      .extract("case", FieldsParser.update("case", publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("case")
        caseSrv
          .update(_.get(caseIdOrNumber).can(Permissions.manageCase), propertyUpdaters)
          .map(_ => Results.NoContent)
      }

  def delete(caseIdOrNumber: String): Action[AnyContent] =
    entryPoint("delete case")
      .authTransaction(db) { implicit request => implicit graph =>
        caseSrv
          .get(caseIdOrNumber)
          .can(Permissions.manageCase)
          .update("status" -> "deleted")
          .map(_ => Results.NoContent)
      }

  def merge(caseIdsOrNumbers: String): Action[AnyContent] =
    entryPoint("merge cases")
      .authTransaction(db) { implicit request => implicit graph =>
        caseIdsOrNumbers
          .split(',')
          .toSeq
          .toTry(
            caseSrv
              .get(_)
              .visible
              .getOrFail()
          )
          .map { cases =>
            val mergedCase = caseSrv.merge(cases)
            Results.Ok(mergedCase.toJson)
          }
      }
}
