package org.thp.thehive.services

import java.util.{Date, List ⇒ JList}

import scala.collection.JavaConverters._
import scala.util.Try

import gremlin.scala._
import javax.inject.{Inject, Singleton}
import org.apache.tinkerpop.gremlin.process.traversal.Path
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.controllers.UpdateOps
import org.thp.scalligraph.models._
import org.thp.scalligraph.services.{EdgeSrv, _}
import org.thp.scalligraph.{EntitySteps, FPath, InternalError, RichJMap, RichOptionTry, RichSeq}
import org.thp.thehive.models._
import shapeless.HNil

@Singleton
class AlertSrv @Inject()(caseSrv: CaseSrv, customFieldSrv: CustomFieldSrv, caseTemplateSrv: CaseTemplateSrv, taskSrv: TaskSrv, userSrv: UserSrv)(
    implicit db: Database)
    extends VertexSrv[Alert, AlertSteps] {

  val alertCustomFieldSrv  = new EdgeSrv[AlertCustomField, Alert, CustomField]
  val alertOrganisationSrv = new EdgeSrv[AlertOrganisation, Alert, Organisation]
  val alertCaseSrv         = new EdgeSrv[AlertCase, Alert, Case]
  val alertCaseTemplateSrv = new EdgeSrv[AlertCaseTemplate, Alert, CaseTemplate]
  val alertObservableSrv   = new EdgeSrv[AlertObservable, Alert, Observable]

  def create(alert: Alert, organisation: Organisation with Entity, customFields: Map[String, Option[Any]], caseTemplate: Option[RichCaseTemplate])(
      implicit graph: Graph,
      authContext: AuthContext): Try[RichAlert] = {
    val createdAlert = create(alert)
    alertOrganisationSrv.create(AlertOrganisation(), createdAlert, organisation)
    caseTemplate.foreach(ct ⇒ alertCaseTemplateSrv.create(AlertCaseTemplate(), createdAlert, ct.caseTemplate))
    customFields.toSeq
      .toTry {
        case (name, Some(value)) ⇒
          for {
            cf  ← customFieldSrv.getOrFail(name)
            acf ← cf.`type`.setValue(AlertCustomField(), value)
            alertCustomField = alertCustomFieldSrv.create(acf, createdAlert, cf)
          } yield CustomFieldWithValue(cf, alertCustomField)
        case (name, _) ⇒
          customFieldSrv.getOrFail(name).map { cf ⇒
            val alertCustomField = alertCustomFieldSrv.create(AlertCustomField(), createdAlert, cf)
            CustomFieldWithValue(cf, alertCustomField)
          }
      }
      .map { cfs ⇒
        RichAlert(createdAlert, organisation.name, cfs, None, caseTemplate.map(_.name))
      }
  }

  def setCustomField(`alert`: Alert with Entity, customFieldName: String, value: Any)(
      implicit graph: Graph,
      authContext: AuthContext): Try[AlertCustomField with Entity] =
    for {
      cf  ← customFieldSrv.getOrFail(customFieldName)
      acf ← setCustomField(`alert`, cf, value)
    } yield acf

  // FIXME add or update
  // TODO do like caseSrv.setCustomField
  def setCustomField(`alert`: Alert with Entity, customField: CustomField with Entity, value: Any)(
      implicit graph: Graph,
      authContext: AuthContext): Try[AlertCustomField with Entity] =
    customField.`type`
      .asInstanceOf[CustomFieldType[Any]]
      .setValue(AlertCustomField(), value)
      .map(alertCustomField ⇒ alertCustomFieldSrv.create(alertCustomField, `alert`, customField))

  def markAsUnread(alertId: String)(implicit graph: Graph, authContext: AuthContext): Unit =
    db.update(graph, authContext, Model.vertex[Alert], alertId, Map(FPath("read") → UpdateOps.SetAttribute(false)))

  def markAsRead(alertId: String)(implicit graph: Graph, authContext: AuthContext): Unit =
    db.update(graph, authContext, Model.vertex[Alert], alertId, Map(FPath("read") → UpdateOps.SetAttribute(true)))

  def followAlert(alertId: String)(implicit graph: Graph, authContext: AuthContext): Unit =
    db.update(graph, authContext, Model.vertex[Alert], alertId, Map(FPath("follow") → UpdateOps.SetAttribute(true)))

  def unfollowAlert(alertId: String)(implicit graph: Graph, authContext: AuthContext): Unit =
    db.update(graph, authContext, Model.vertex[Alert], alertId, Map(FPath("read") → UpdateOps.SetAttribute(false)))

  def createCase(alert: RichAlert, user: Option[User with Entity], organisation: Organisation with Entity)(
      implicit graph: Graph,
      authContext: AuthContext): Try[RichCase] =
    for {

      caseTemplate ← alert.caseTemplate
        .map(caseTemplateSrv.get(_).richCaseTemplate.getOrFail())
        .flip
      customField = caseTemplate
        .fold[Seq[(String, Option[Any])]](Nil) { ct ⇒
          ct.customFields.map(f ⇒ f.name → f.value)
        }
        .toMap ++ alert.customFields.map(f ⇒ f.name → f.value)
      case0 = Case(
        number = 0,
        title = caseTemplate.flatMap(_.titlePrefix).getOrElse("") + alert.title,
        description = alert.description,
        severity = alert.severity,
        startDate = new Date,
        endDate = None,
        tags = alert.tags,
        flag = alert.flag,
        tlp = alert.tlp,
        pap = alert.pap,
        status = CaseStatus.open,
        summary = None
      )

      createdCase ← caseSrv.create(case0, user, organisation, customField, caseTemplate)

      _ = caseTemplate.foreach { ct ⇒
        caseTemplateSrv.get(ct.caseTemplate).tasks.toList().foreach { task ⇒
          taskSrv.create(task, createdCase.`case`)
        }
      }

      _ = importObservables(alert.alert, createdCase.`case`)
      _ = alertCaseSrv.create(AlertCase(), alert.alert, createdCase.`case`)
    } yield createdCase

  def mergeInCase(alertId: String, caseId: String)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    // TODO add observables
    for {
      alert ← getOrFail(alertId)
      case0 ← caseSrv.getOrFail(caseId)
      description = case0.description + s"\n  \n#### Merged with alert #${alert.sourceRef} ${alert.title}\n\n${alert.description.trim}"
      _           = caseSrv.update(caseId, "description", description)
      _           = importObservables(alert, case0)
    } yield ()

  def importObservables(alert: Alert with Entity, `case`: Case with Entity) = {
    // TODO add observables
  }

  override def steps(raw: GremlinScala[Vertex])(implicit graph: Graph): AlertSteps = new AlertSteps(raw)
}

@EntitySteps[Alert]
class AlertSteps(raw: GremlinScala[Vertex])(implicit db: Database, graph: Graph) extends BaseVertexSteps[Alert, AlertSteps](raw) {
  override def newInstance(raw: GremlinScala[Vertex]): AlertSteps = new AlertSteps(raw)

  def organisation: OrganisationSteps = new OrganisationSteps(raw.inTo[AlertOrganisation])

  def visible(implicit authContext: AuthContext): AlertSteps = newInstance(
    raw.filter(_.outTo[AlertOrganisation].inTo[RoleOrganisation].inTo[UserRole].has(Key("login") of authContext.userId))
  )

  def can(permission: Permission)(implicit authContext: AuthContext): AlertSteps = newInstance(
    raw.filter(
      _.outTo[AlertOrganisation]
        .inTo[OrganisationShare]
        .inTo[RoleOrganisation]
        .has(Key("permissions") of permission)
        .inTo[UserRole]
        .has(Key("login") of authContext.userId))
  )

  def alertUserOrganisation(permission: Permission)(implicit authContext: AuthContext): ScalarSteps[(RichAlert, Organisation with Entity)] = {
    val alertLabel            = StepLabel[Vertex]()
    val organisationLabel     = StepLabel[Vertex]()
    val customFieldLabel      = StepLabel[JList[Path]]()
    val caseIdLabel           = StepLabel[JList[String]]()
    val caseTemplateNameLabel = StepLabel[JList[String]]()
    new ScalarSteps(
      raw
        .asInstanceOf[GremlinScala.Aux[Vertex, HNil]]
        .`match`(
          _.as(alertLabel).out("AlertOrganisation").as(organisationLabel),
          _.as(organisationLabel)
            .inTo[OrganisationShare]
            .inTo[RoleOrganisation]
            .has(Key("permissions") of permission)
            .inTo[UserRole]
            .has(Key("login") of authContext.userId),
          _.as(alertLabel).outToE[AlertCustomField].inV().path.fold.as(customFieldLabel),
          _.as(alertLabel).outTo[AlertCase].values[String]("_id").fold.as(caseIdLabel),
          _.as(alertLabel).outTo[AlertCaseTemplate].values[String]("name").fold.as(caseTemplateNameLabel)
        )
        .select(alertLabel.name, organisationLabel.name, customFieldLabel.name, caseIdLabel.name, caseTemplateNameLabel.name)
        .map { resultMap ⇒
          val customFieldValues = resultMap
            .getValue(customFieldLabel)
            .asScala
            .map(_.asScala.takeRight(2).toList.asInstanceOf[List[Element]])
            .map {
              case List(acf, cf) ⇒ CustomFieldWithValue(cf.as[CustomField], acf.as[AlertCustomField])
              case _             ⇒ throw InternalError("Not possible")
            }
          val organisation = resultMap.getValue(organisationLabel).as[Organisation]
          RichAlert(
            resultMap.getValue(alertLabel).as[Alert],
            organisation.name,
            customFieldValues,
            atMostOneOf(resultMap.getValue(caseIdLabel)),
            atMostOneOf(resultMap.getValue(caseTemplateNameLabel))
          ) → organisation
        })
  }

  def richAlert: ScalarSteps[RichAlert] =
    ScalarSteps(
      raw
        .project(
          _.apply(By[Vertex]())
            .and(By(__[Vertex].outTo[AlertOrganisation].values[String]("name").fold))
            .and(By(__[Vertex].outToE[AlertCustomField].inV().path.fold))
            .and(By(__[Vertex].outTo[AlertCase].values[String]("_id").fold))
            .and(By(__[Vertex].outTo[AlertCaseTemplate].values[String]("name").fold)))
        .map {
          case (alert, organisation, customFields, caseId, caseTemplate) ⇒
            val customFieldValues = (customFields: java.util.List[Path]).asScala
              .map(_.asScala.takeRight(2).toList.asInstanceOf[List[Element]])
              .map {
                case List(acf, cf) ⇒ CustomFieldWithValue(cf.as[CustomField], acf.as[AlertCustomField])
                case _             ⇒ throw InternalError("Not possible")
              }
            RichAlert(
              alert.as[Alert],
              onlyOneOf[String](organisation),
              customFieldValues,
              atMostOneOf[String](caseId),
              atMostOneOf[String](caseTemplate)
            )
        }
    )
}
