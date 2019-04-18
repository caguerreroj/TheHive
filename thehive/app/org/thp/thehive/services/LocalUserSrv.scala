package org.thp.thehive.services

import scala.util.{Failure, Success, Try}

import play.api.mvc.RequestHeader

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph.auth.{AuthContext, AuthContextImpl}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.{AuthenticationError, Instance}
import org.thp.thehive.models.Permissions

@Singleton
class LocalUserSrv @Inject()(db: Database, userSrv: UserSrv) extends org.thp.scalligraph.auth.UserSrv {

  override def getFromId(request: RequestHeader, userId: String): Try[AuthContext] =
    db.tryTransaction { implicit graph ⇒
      userSrv.initSteps
        .get(userId)
        .getAuthContext(Instance.getRequestId(request))
        .getOrFail()
    }

  override def getInitialUser(request: RequestHeader): Try[AuthContext] =
    db.tryTransaction { implicit graph ⇒
      if (userSrv.count > 0)
        Failure(AuthenticationError(s"Use of initial user is forbidden because users exist in database"))
      else Success(initialAuthContext)
    }

  override val initialAuthContext: AuthContext =
    AuthContextImpl(UserSrv.initUser, "Default admin user", "default", Instance.getInternalId, Permissions.all)
}
