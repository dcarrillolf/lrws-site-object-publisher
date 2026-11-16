package com.custom.site.object.publisher.internal.jaxrs;

import com.custom.site.object.publisher.internal.service.SitePublisher;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.security.auth.PrincipalException;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.UserLocalService;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.Set;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * REST API in /o/site-object-publisher. {@code groupId} is always the source
 * (working) site and {@code targetGroupId} the target site.
 *
 * <pre>
 * GET    /sites?groupId=
 * GET    /object-definitions?groupId=
 * GET    /entries?groupId=&amp;targetGroupId=&amp;objectDefinitionId=&amp;onlyChanged=&amp;search=&amp;page=&amp;pageSize=
 * POST   /entries/{id}/publish?groupId=&amp;targetGroupId=
 * POST   /entries/{id}/restore?groupId=&amp;targetGroupId=
 * POST   /entries/{id}/delete?groupId=&amp;targetGroupId=
 * GET    /publications?groupId=
 * GET    /publication?groupId=&amp;targetGroupId=&amp;name=
 * DELETE /publication?groupId=&amp;name=
 * POST   /publication/publish?groupId=&amp;targetGroupId=&amp;name=
 * POST   /items?groupId=&amp;name=&amp;objectEntryId=&amp;action=publish|delete
 * DELETE /items/{itemId}?groupId=
 * GET    /history?groupId=
 * GET    /history/items?groupId=&amp;sourceGroupId=&amp;name=
 * DELETE /history?groupId=&amp;sourceGroupId=&amp;name=
 * </pre>
 *
 * @author Custom Site Object Publisher
 */
@Component(
	property = {
		"auth.verifier.guest.allowed=false", "liferay.jackson=false",
		"liferay.oauth2=false", "osgi.jaxrs.application.base=/site-object-publisher",
		"osgi.jaxrs.name=Site.Object.Publisher.REST"
	},
	service = Application.class
)
public class SiteObjectPublisherApplication extends Application {

	@POST
	@Path("/items")
	@Produces(MediaType.APPLICATION_JSON)
	public Response addItem(
		@QueryParam("groupId") long groupId, @QueryParam("name") String name,
		@QueryParam("objectEntryId") long objectEntryId,
		@DefaultValue("publish") @QueryParam("action") String action) {

		return _call(
			groupId,
			user -> _sitePublisher.addItem(
				user.getUserId(), groupId, name, objectEntryId,
				SitePublisher.ACTION_DELETE.equals(action) ?
					SitePublisher.ACTION_DELETE :
						SitePublisher.ACTION_PUBLISH));
	}

	@DELETE
	@Path("/publication")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deletePublication(
		@QueryParam("groupId") long groupId, @QueryParam("name") String name) {

		return _call(
			groupId,
			user -> _sitePublisher.deletePublication(
				user.getCompanyId(), groupId, name));
	}

	@DELETE
	@Path("/history")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteHistory(
		@QueryParam("groupId") long groupId,
		@QueryParam("sourceGroupId") long sourceGroupId,
		@QueryParam("name") String name) {

		return _call(
			groupId,
			user -> _sitePublisher.deletePublicationHistory(
				user.getCompanyId(), groupId,
				(sourceGroupId > 0) ? sourceGroupId : groupId, name));
	}

	@GET
	@Path("/entries")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getEntries(
		@QueryParam("groupId") long groupId,
		@QueryParam("targetGroupId") long targetGroupId,
		@QueryParam("objectDefinitionId") long objectDefinitionId,
		@DefaultValue("true") @QueryParam("onlyChanged") boolean onlyChanged,
		@QueryParam("search") String search,
		@DefaultValue("1") @QueryParam("page") int page,
		@DefaultValue("50") @QueryParam("pageSize") int pageSize) {

		return _call(
			groupId,
			user -> _sitePublisher.getEntries(
				user.getCompanyId(), groupId, targetGroupId,
				objectDefinitionId, onlyChanged, search, page, pageSize,
				user.getLocale()));
	}

	@GET
	@Path("/history")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getHistory(@QueryParam("groupId") long groupId) {
		return _call(
			groupId,
			user -> _sitePublisher.getPublicationHistory(
				user.getCompanyId(), groupId));
	}

	@GET
	@Path("/history/items")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getHistoryItems(
		@QueryParam("groupId") long groupId,
		@QueryParam("sourceGroupId") long sourceGroupId,
		@QueryParam("name") String name) {

		return _call(
			groupId,
			user -> _sitePublisher.getPublicationHistoryItems(
				user.getCompanyId(), groupId,
				(sourceGroupId > 0) ? sourceGroupId : groupId, name,
				user.getLocale()));
	}

	@GET
	@Path("/object-definitions")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getObjectDefinitions(@QueryParam("groupId") long groupId) {
		return _call(
			groupId,
			user -> _sitePublisher.getObjectDefinitions(
				user.getCompanyId(), user.getLocale()));
	}

	@GET
	@Path("/publication")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPublication(
		@QueryParam("groupId") long groupId,
		@QueryParam("targetGroupId") long targetGroupId,
		@QueryParam("name") String name) {

		return _call(
			groupId,
			user -> _sitePublisher.getPublication(
				user.getCompanyId(), groupId, targetGroupId, name,
				user.getLocale()));
	}

	@GET
	@Path("/publications")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPublications(@QueryParam("groupId") long groupId) {
		return _call(
			groupId,
			user -> _sitePublisher.getPublications(
				user.getCompanyId(), groupId));
	}

	@Override
	public Set<Object> getSingletons() {
		return Collections.singleton(this);
	}

	@GET
	@Path("/sites")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSites(@QueryParam("groupId") long groupId) {
		return _call(
			groupId,
			user -> _sitePublisher.getSites(user.getCompanyId(), groupId));
	}

	@POST
	@Path("/entries/{id}/publish")
	@Produces(MediaType.APPLICATION_JSON)
	public Response publishEntry(
		@PathParam("id") long id, @QueryParam("groupId") long groupId,
		@QueryParam("targetGroupId") long targetGroupId) {

		return _call(
			groupId,
			user -> _sitePublisher.publishEntry(
				user.getUserId(), groupId, targetGroupId, id));
	}

	@POST
	@Path("/entries/{id}/delete")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteEntry(
		@PathParam("id") long id, @QueryParam("groupId") long groupId,
		@QueryParam("targetGroupId") long targetGroupId) {

		return _call(
			groupId,
			user -> _sitePublisher.deleteEntry(groupId, targetGroupId, id));
	}

	@POST
	@Path("/entries/{id}/restore")
	@Produces(MediaType.APPLICATION_JSON)
	public Response restoreEntry(
		@PathParam("id") long id, @QueryParam("groupId") long groupId,
		@QueryParam("targetGroupId") long targetGroupId) {

		return _call(
			groupId,
			user -> _sitePublisher.restoreEntry(
				user.getUserId(), groupId, targetGroupId, id));
	}

	@POST
	@Path("/publication/publish")
	@Produces(MediaType.APPLICATION_JSON)
	public Response publishPublication(
		@QueryParam("groupId") long groupId,
		@QueryParam("targetGroupId") long targetGroupId,
		@QueryParam("name") String name) {

		return _call(
			groupId,
			user -> _sitePublisher.publishPublication(
				user.getUserId(), user.getCompanyId(), groupId, targetGroupId,
				name));
	}

	@DELETE
	@Path("/items/{itemId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response removeItem(
		@PathParam("itemId") long itemId,
		@QueryParam("groupId") long groupId) {

		return _call(
			groupId, user -> _sitePublisher.removeItem(groupId, itemId));
	}

	private Response _call(long groupId, Call call) {
		try {
			PermissionChecker permissionChecker =
				PermissionThreadLocal.getPermissionChecker();

			if ((permissionChecker == null) ||
				!permissionChecker.isSignedIn()) {

				return _error(Response.Status.UNAUTHORIZED, "Sign in");
			}

			User user = _userLocalService.getUser(
				permissionChecker.getUserId());

			_sitePublisher.checkPermission(user.getCompanyId(), groupId);

			return Response.ok(
				String.valueOf(call.call(user)), MediaType.APPLICATION_JSON
			).build();
		}
		catch (PrincipalException principalException) {
			return _error(
				Response.Status.FORBIDDEN, principalException.getMessage());
		}
		catch (PortalException portalException) {

			// Expected validation errors: their message is meant for the user

			if (_log.isDebugEnabled()) {
				_log.debug(portalException);
			}

			return _error(
				Response.Status.BAD_REQUEST,
				(portalException.getMessage() == null) ?
					"The request could not be processed" :
						portalException.getMessage());
		}
		catch (Exception exception) {

			// Unexpected errors: the detail stays in the log

			_log.error(exception);

			return _error(
				Response.Status.INTERNAL_SERVER_ERROR,
				"Unexpected error. See the server log for details.");
		}
	}

	private Response _error(Response.Status status, String message) {
		return Response.status(
			status
		).entity(
			_jsonFactory.createJSONObject(
			).put(
				"error", message
			).toString()
		).type(
			MediaType.APPLICATION_JSON
		).build();
	}

	private static final Log _log = LogFactoryUtil.getLog(
		SiteObjectPublisherApplication.class);

	@Reference
	private JSONFactory _jsonFactory;

	@Reference
	private SitePublisher _sitePublisher;

	@Reference
	private UserLocalService _userLocalService;

	@FunctionalInterface
	private interface Call {

		public Object call(User user) throws PortalException;

	}

}
