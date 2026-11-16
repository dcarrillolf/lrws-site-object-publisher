package com.custom.site.object.publisher.internal.service;

import com.custom.site.object.publisher.constants.SiteObjectPublisherPortletKeys;

import com.liferay.object.constants.ObjectDefinitionConstants;
import com.liferay.object.constants.ObjectRelationshipConstants;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.model.ObjectField;
import com.liferay.object.model.ObjectRelationship;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.object.service.ObjectFieldLocalService;
import com.liferay.object.service.ObjectRelationshipLocalService;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactory;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.model.Company;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.model.GroupConstants;
import com.liferay.portal.kernel.model.ResourceConstants;
import com.liferay.portal.kernel.model.ResourcePermission;
import com.liferay.portal.kernel.model.Role;
import com.liferay.portal.kernel.model.role.RoleConstants;
import com.liferay.portal.kernel.security.auth.PrincipalException;
import com.liferay.portal.kernel.security.permission.ActionKeys;
import com.liferay.portal.kernel.security.permission.PermissionChecker;
import com.liferay.portal.kernel.security.permission.PermissionThreadLocal;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.service.GroupLocalService;
import com.liferay.portal.kernel.service.ResourcePermissionLocalService;
import com.liferay.portal.kernel.service.RoleLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.Portal;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.io.Serializable;

import java.net.URLEncoder;

import java.nio.charset.StandardCharsets;

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * Publishes object entries from a working site (source, "site B") to a
 * published site (target, "site A"), from approved to approved. Entries are
 * matched by external reference code (ERC): if the target has an entry with the
 * same ERC it is overwritten, otherwise it is created. Relationships are
 * resolved through the ERC of the related entry.
 *
 * Publications are named groups of entries to publish or delete, stored in the
 * publication item object (one row per entry and publication). A publication is
 * active while it has pending items. When it is published, its items are
 * archived: they are renamed to "name · date" and marked as published or error,
 * so the name can be reused and the publication moves to the history.
 *
 * @author Custom Site Object Publisher
 */
@Component(service = SitePublisher.class)
public class SitePublisher {

	public static final String ACTION_DELETE = "delete";

	public static final String ACTION_PUBLISH = "publish";

	public static final String STATUS_ERROR = "error";

	public static final String STATUS_PENDING = "pending";

	public static final String STATUS_PUBLISHED = "published";

	/**
	 * Optional OSGi configuration (PID = this class name), for example in
	 * osgi/configs/com.custom.site.object.publisher.internal.service.SitePublisher.config:
	 * publicationItemObjectDefinitionName="C_MyPublicationItem"
	 */
	@Activate
	@Modified
	protected void activate(Map<String, Object> properties) {
		String name = GetterUtil.getString(
			properties.get("publicationItemObjectDefinitionName"),
			SiteObjectPublisherPortletKeys.
				PUBLICATION_ITEM_OBJECT_DEFINITION_NAME);

		if (!name.startsWith("C_")) {
			name = "C_" + name;
		}

		_publicationItemObjectDefinitionName = name;
	}

	public JSONObject addItem(
			long userId, long sourceGroupId, String publication,
			long objectEntryId, String action)
		throws PortalException {

		if (Validator.isNull(publication)) {
			throw new PortalException("Enter the publication name");
		}

		ObjectEntry objectEntry = _getSourceObjectEntry(
			sourceGroupId, objectEntryId);

		// Delete wins over modifications: a delete mark removes the entry's pending
		// publish marks (in any publication), and an entry marked for deletion cannot
		// be included until that mark is removed

		int removed = 0;

		for (ObjectEntry pendingItemObjectEntry :
				_getPendingItems(objectEntry.getCompanyId(), objectEntryId)) {

			boolean samePublication = _getString(
				pendingItemObjectEntry, "publication"
			).equals(
				publication.trim()
			);
			String pendingAction = _getString(
				pendingItemObjectEntry, "itemAction");

			if (ACTION_DELETE.equals(action)) {
				if (!samePublication &&
					pendingAction.equals(ACTION_PUBLISH)) {

					_objectEntryLocalService.deleteObjectEntry(
						pendingItemObjectEntry);

					removed++;
				}
			}
			else if (pendingAction.equals(ACTION_DELETE)) {
				throw new PortalException(
					"\"" + _getLabel(objectEntry) +
						"\" is marked for deletion in \"" +
							_getString(pendingItemObjectEntry, "publication") +
								"\". Remove that mark before including it.");
			}
		}

		ObjectEntry itemObjectEntry = _fetchPendingItem(
			objectEntry.getCompanyId(), publication, objectEntryId);

		Map<String, Serializable> values = new HashMap<>();

		values.put("entryERC", objectEntry.getExternalReferenceCode());
		values.put("entryLabel", _getLabel(objectEntry));
		values.put("itemAction", action);
		values.put("itemStatus", STATUS_PENDING);
		values.put("message", "");
		values.put("objectDefinitionId", objectEntry.getObjectDefinitionId());
		values.put("publication", publication.trim());
		values.put("sourceEntryId", objectEntryId);
		values.put("sourceGroupId", sourceGroupId);

		ServiceContext serviceContext = _createServiceContext(
			objectEntry.getCompanyId(), 0, userId);

		if (itemObjectEntry != null) {
			_objectEntryLocalService.partialUpdateObjectEntry(
				userId, itemObjectEntry.getObjectEntryId(), 0, values,
				serviceContext);
		}
		else {
			_objectEntryLocalService.addObjectEntry(
				0, userId,
				_getItemObjectDefinition(
					objectEntry.getCompanyId()
				).getObjectDefinitionId(),
				0, objectEntry.getDefaultLanguageId(), values, serviceContext);
		}

		return _message(
			(ACTION_DELETE.equals(action) ? "Marked for deletion" :
				"Included") + " in \"" + publication + "\": " +
					_getLabel(objectEntry) +
						((removed > 0) ?
							" (" + removed +
								" publish mark(s) removed from other publications)" :
									""),
			"added");
	}

	public void checkPermission(long companyId, long groupId)
		throws PortalException {

		PermissionChecker permissionChecker =
			PermissionThreadLocal.getPermissionChecker();

		if ((permissionChecker == null) || !permissionChecker.isSignedIn()) {
			throw new PrincipalException.MustBeAuthenticated(0);
		}

		// Only administrators (of the instance or of the source site)

		if (permissionChecker.isCompanyAdmin(companyId) ||
			((groupId > 0) && permissionChecker.isGroupAdmin(groupId))) {

			return;
		}

		throw new PrincipalException(
			"Only site administrators can publish");
	}

	/**
	 * Work view, one page at a time. Every source entry is compared with the
	 * target (mostly a date check) to apply "only with changes", but the JSON
	 * and URLs are only built for the requested page. The optional search text
	 * keeps only the entries that contain it in any field or in their ERC.
	 */
	public JSONObject getEntries(
			long companyId, long sourceGroupId, long targetGroupId,
			long objectDefinitionId, boolean onlyChanged, String search,
			int page, int pageSize, Locale locale)
		throws PortalException {

		ObjectDefinition objectDefinition = _getPublishableObjectDefinition(
			companyId, objectDefinitionId);

		if (targetGroupId > 0) {
			_checkTarget(sourceGroupId, targetGroupId);
		}

		Map<Long, List<ObjectEntry>> itemsByEntry = new HashMap<>();

		for (ObjectEntry itemObjectEntry : _getItems(companyId)) {
			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING)) {

				itemsByEntry.computeIfAbsent(
					_getLong(itemObjectEntry, "sourceEntryId"),
					key -> new ArrayList<>()
				).add(
					itemObjectEntry
				);
			}
		}

		// Target entries loaded in one query and indexed by ERC

		Map<String, ObjectEntry> targetObjectEntries = new HashMap<>();

		if (targetGroupId > 0) {
			for (ObjectEntry targetObjectEntry :
					_objectEntryLocalService.getObjectEntries(
						targetGroupId, objectDefinitionId, -1, -1)) {

				if (targetObjectEntry.isHead() &&
					Validator.isNotNull(
						targetObjectEntry.getExternalReferenceCode())) {

					targetObjectEntries.put(
						targetObjectEntry.getExternalReferenceCode(),
						targetObjectEntry);
				}
			}
		}

		List<Object[]> rows = new ArrayList<>();

		for (ObjectEntry objectEntry :
				_objectEntryLocalService.getObjectEntries(
					sourceGroupId, objectDefinitionId, -1, -1)) {

			if (!objectEntry.isHead() || !_matches(objectEntry, search)) {
				continue;
			}

			ObjectEntry targetObjectEntry = targetObjectEntries.get(
				objectEntry.getExternalReferenceCode());

			String targetState = _getTargetState(
				objectDefinition, objectEntry, targetObjectEntry);

			List<ObjectEntry> itemObjectEntries = itemsByEntry.getOrDefault(
				objectEntry.getObjectEntryId(), new ArrayList<>());

			if (onlyChanged && targetState.equals("same") &&
				itemObjectEntries.isEmpty()) {

				continue;
			}

			rows.add(
				new Object[] {objectEntry, targetObjectEntry, targetState,
					itemObjectEntries});
		}

		rows.sort(
			Comparator.comparing(
				(Object[] row) -> {
					Date modifiedDate = ((ObjectEntry)row[0]).getModifiedDate();

					return (modifiedDate == null) ? 0L : modifiedDate.getTime();
				},
				Comparator.reverseOrder()));

		pageSize = Math.max(1, Math.min(pageSize, 500));

		int lastPage = Math.max(1, (rows.size() + pageSize - 1) / pageSize);

		page = Math.max(1, Math.min(page, lastPage));

		JSONArray itemsJSONArray = _jsonFactory.createJSONArray();

		for (Object[] row :
				rows.subList(
					(page - 1) * pageSize,
					Math.min(rows.size(), page * pageSize))) {

			@SuppressWarnings("unchecked")
			List<ObjectEntry> itemObjectEntries = (List<ObjectEntry>)row[3];

			itemsJSONArray.put(
				_toJSONObject(
					objectDefinition, (ObjectEntry)row[0], (ObjectEntry)row[1],
					(String)row[2], itemObjectEntries, sourceGroupId,
					targetGroupId, locale));
		}

		return _jsonFactory.createJSONObject(
		).put(
			"items", itemsJSONArray
		).put(
			"lastPage", lastPage
		).put(
			"page", page
		).put(
			"pageSize", pageSize
		).put(
			"totalCount", rows.size()
		);
	}


	public JSONArray getObjectDefinitions(long companyId, Locale locale) {
		JSONArray jsonArray = _jsonFactory.createJSONArray();

		for (ObjectDefinition objectDefinition :
				_objectDefinitionLocalService.getObjectDefinitions(
					companyId, true, false,
					WorkflowConstants.STATUS_APPROVED)) {

			if (!Objects.equals(
					objectDefinition.getScope(),
					ObjectDefinitionConstants.SCOPE_SITE) ||
				objectDefinition.isUnmodifiableSystemObject()) {

				continue;
			}

			jsonArray.put(
				_jsonFactory.createJSONObject(
				).put(
					"id", objectDefinition.getObjectDefinitionId()
				).put(
					"label", objectDefinition.getLabel(locale)
				));
		}

		return jsonArray;
	}

	/**
	 * Pending entries of an active publication, including the ones that no longer
	 * exist in the source. Already processed items with the same name are not
	 * shown: they are in the history.
	 */
	public JSONObject getPublication(
			long companyId, long sourceGroupId, long targetGroupId,
			String publication, Locale locale)
		throws PortalException {

		if (targetGroupId > 0) {
			_checkTarget(sourceGroupId, targetGroupId);
		}

		JSONArray itemsJSONArray = _jsonFactory.createJSONArray();
		JSONArray missingJSONArray = _jsonFactory.createJSONArray();

		for (ObjectEntry itemObjectEntry :
				_getPublicationItems(companyId, sourceGroupId, publication)) {

			if (!_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING)) {

				continue;
			}

			ObjectEntry objectEntry = _objectEntryLocalService.fetchObjectEntry(
				_getLong(itemObjectEntry, "sourceEntryId"));

			JSONObject itemJSONObject = _toItemJSONObject(itemObjectEntry);

			if (objectEntry == null) {
				missingJSONArray.put(itemJSONObject);

				continue;
			}

			ObjectDefinition objectDefinition =
				_objectDefinitionLocalService.getObjectDefinition(
					objectEntry.getObjectDefinitionId());
			ObjectEntry targetObjectEntry = _fetchTarget(
				objectEntry, targetGroupId);

			String targetState = _getTargetState(
				objectDefinition, objectEntry, targetObjectEntry);

			JSONObject rowJSONObject = _toJSONObject(
				objectDefinition, objectEntry, targetObjectEntry, targetState,
				new ArrayList<>(), sourceGroupId, targetGroupId, locale);

			itemsJSONArray.put(
				rowJSONObject.put(
					"item", itemJSONObject
				).put(
					"objectDefinitionLabel", objectDefinition.getLabel(locale)
				));
		}

		return _jsonFactory.createJSONObject(
		).put(
			"items", itemsJSONArray
		).put(
			"missing", missingJSONArray
		).put(
			"publication", publication
		);
	}

	/**
	 * Active publications: the ones with at least one pending item.
	 */
	public JSONArray getPublications(long companyId, long sourceGroupId) {
		Map<String, int[]> counts = new TreeMap<>();

		for (ObjectEntry itemObjectEntry : _getItems(companyId)) {
			if (_getLong(itemObjectEntry, "sourceGroupId") != sourceGroupId) {
				continue;
			}

			int[] count = counts.computeIfAbsent(
				_getString(itemObjectEntry, "publication"),
				key -> new int[2]);

			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING)) {

				count[0]++;
			}
			else {
				count[1]++;
			}
		}

		JSONArray jsonArray = _jsonFactory.createJSONArray();

		for (Map.Entry<String, int[]> entry : counts.entrySet()) {
			if (entry.getValue()[0] == 0) {
				continue;
			}

			jsonArray.put(
				_jsonFactory.createJSONObject(
				).put(
					"done", entry.getValue()[1]
				).put(
					"name", entry.getKey()
				).put(
					"pending", entry.getValue()[0]
				));
		}

		return jsonArray;
	}

	/**
	 * Past publications where the site is the source (sent) or the target
	 * (received): groups of items without pending ones (archived when
	 * published), grouped by source site and name, newest first. The items are
	 * company-scoped, so they are kept, and listed, even if the other site was
	 * deleted.
	 */
	public JSONArray getPublicationHistory(long companyId, long groupId) {
		Map<String, List<ObjectEntry>> publications = new HashMap<>();

		for (ObjectEntry itemObjectEntry : _getItems(companyId)) {
			long sourceGroupId = _getLong(itemObjectEntry, "sourceGroupId");

			if ((sourceGroupId != groupId) &&
				(_getLong(itemObjectEntry, "targetGroupId") != groupId)) {

				continue;
			}

			publications.computeIfAbsent(
				sourceGroupId + "|" +
					_getString(itemObjectEntry, "publication"),
				key -> new ArrayList<>()
			).add(
				itemObjectEntry
			);
		}

		List<JSONObject> rows = new ArrayList<>();

		for (List<ObjectEntry> itemObjectEntries : publications.values()) {
			int errors = 0;
			long publishedDate = 0;
			int published = 0;

			for (ObjectEntry itemObjectEntry : itemObjectEntries) {
				String status = _getString(itemObjectEntry, "itemStatus");

				if (status.equals(STATUS_PENDING)) {
					published = -1;

					break;
				}

				if (status.equals(STATUS_ERROR)) {
					errors++;
				}
				else {
					published++;
				}

				Date modifiedDate = itemObjectEntry.getModifiedDate();

				if (modifiedDate != null) {
					publishedDate = Math.max(
						publishedDate, modifiedDate.getTime());
				}
			}

			if (published < 0) {
				continue;
			}

			ObjectEntry firstItemObjectEntry = itemObjectEntries.get(0);

			long sourceGroupId = _getLong(
				firstItemObjectEntry, "sourceGroupId");
			long targetGroupId = _getLong(
				firstItemObjectEntry, "targetGroupId");

			boolean sent = sourceGroupId == groupId;

			long otherGroupId = sent ? targetGroupId : sourceGroupId;

			rows.add(
				_jsonFactory.createJSONObject(
				).put(
					"direction", sent ? "sent" : "received"
				).put(
					"errors", errors
				).put(
					"name", _getString(firstItemObjectEntry, "publication")
				).put(
					"otherGroupId", otherGroupId
				).put(
					"otherGroupName", _getGroupName(otherGroupId)
				).put(
					"published", published
				).put(
					"publishedDate", publishedDate
				).put(
					"sourceGroupId", sourceGroupId
				).put(
					"targetGroupId", targetGroupId
				));
		}

		rows.sort(
			Comparator.comparing(
				(JSONObject row) -> row.getLong("publishedDate"),
				Comparator.reverseOrder()));

		JSONArray jsonArray = _jsonFactory.createJSONArray();

		rows.forEach(jsonArray::put);

		return jsonArray;
	}

	/**
	 * Detail of a past publication, using only what was recorded in its items
	 * (plus the link to the target entry if it still exists).
	 */
	public JSONArray getPublicationHistoryItems(
		long companyId, long groupId, long sourceGroupId, String publication,
		Locale locale) {

		JSONArray jsonArray = _jsonFactory.createJSONArray();

		for (ObjectEntry itemObjectEntry :
				_getPublicationItems(companyId, sourceGroupId, publication)) {

			// Only publications sent or received by the current site

			if ((sourceGroupId != groupId) &&
				(_getLong(itemObjectEntry, "targetGroupId") != groupId)) {

				continue;
			}

			JSONObject itemJSONObject = _toItemJSONObject(itemObjectEntry);

			ObjectDefinition objectDefinition =
				_objectDefinitionLocalService.fetchObjectDefinition(
					_getLong(itemObjectEntry, "objectDefinitionId"));

			if (objectDefinition != null) {
				itemJSONObject.put(
					"objectDefinitionLabel", objectDefinition.getLabel(locale));

				long targetGroupId = _getLong(itemObjectEntry, "targetGroupId");
				String erc = _getString(itemObjectEntry, "entryERC");

				if ((targetGroupId > 0) && Validator.isNotNull(erc)) {
					itemJSONObject.put(
						"targetURL",
						_getPublicURL(
							_groupLocalService.fetchGroup(targetGroupId),
							objectDefinition,
							_objectEntryLocalService.fetchObjectEntry(
								erc, targetGroupId,
								objectDefinition.getObjectDefinitionId()),
							locale));
				}
			}

			jsonArray.put(itemJSONObject);
		}

		return jsonArray;
	}

	/**
	 * Deletes an active publication: removes its pending items (the entries are
	 * unassigned). Entries and history are not changed.
	 */
	public JSONObject deletePublication(
			long companyId, long sourceGroupId, String publication)
		throws PortalException {

		int removed = 0;

		for (ObjectEntry itemObjectEntry :
				_getPublicationItems(companyId, sourceGroupId, publication)) {

			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING)) {

				_objectEntryLocalService.deleteObjectEntry(itemObjectEntry);

				removed++;
			}
		}

		if (removed == 0) {
			throw new PortalException(
				"\"" + publication + "\" has no pending items");
		}

		return _message(
			"Publication \"" + publication + "\" deleted: " + removed +
				" entry(ies) unassigned",
			"removed");
	}

	/**
	 * Deletes a past publication (its items) from the history. Source and target
	 * entries are not changed.
	 */
	public JSONObject deletePublicationHistory(
			long companyId, long groupId, long sourceGroupId,
			String publication)
		throws PortalException {

		List<ObjectEntry> itemObjectEntries = _getPublicationItems(
			companyId, sourceGroupId, publication);

		// From the source site, or from the target site if the source site no
		// longer exists

		if (sourceGroupId != groupId) {
			boolean target = false;

			for (ObjectEntry itemObjectEntry : itemObjectEntries) {
				if (_getLong(itemObjectEntry, "targetGroupId") == groupId) {
					target = true;
				}
			}

			if (!target ||
				(_groupLocalService.fetchGroup(sourceGroupId) != null)) {

				throw new PrincipalException(
					"Past publications can only be deleted from their source " +
						"site, or from their target site once the source " +
							"site is deleted");
			}
		}

		for (ObjectEntry itemObjectEntry : itemObjectEntries) {
			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING)) {

				throw new PortalException(
					"\"" + publication + "\" has pending items");
			}
		}

		for (ObjectEntry itemObjectEntry : itemObjectEntries) {
			_objectEntryLocalService.deleteObjectEntry(itemObjectEntry);
		}

		return _message(
			"Deleted from history: \"" + publication + "\"", "removed");
	}

	public JSONArray getSites(long companyId, long sourceGroupId) {
		JSONArray jsonArray = _jsonFactory.createJSONArray();

		for (Group group :
				_groupLocalService.getGroups(
					companyId, GroupConstants.DEFAULT_PARENT_GROUP_ID, true)) {

			if (!group.isSite() || !group.isActive() ||
				group.isCompany() ||
				(group.getGroupId() == sourceGroupId) ||
				!_isAdmin(group)) {

				continue;
			}

			jsonArray.put(
				_jsonFactory.createJSONObject(
				).put(
					"friendlyURL", group.getFriendlyURL()
				).put(
					"id", group.getGroupId()
				).put(
					"name", _getGroupName(group)
				));
		}

		return jsonArray;
	}

	/**
	 * Publishes (or deletes in the target) all pending entries of a publication:
	 * parent objects first, then children, and deletions last.
	 */
	public JSONObject publishPublication(
			long userId, long companyId, long sourceGroupId,
			long targetGroupId, String publication)
		throws PortalException {

		_checkTarget(sourceGroupId, targetGroupId);

		List<ObjectEntry> itemObjectEntries = new ArrayList<>();

		for (ObjectEntry itemObjectEntry :
				_getPublicationItems(companyId, sourceGroupId, publication)) {

			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING)) {

				itemObjectEntries.add(itemObjectEntry);
			}
		}

		itemObjectEntries.sort(
			Comparator.comparing(
				(ObjectEntry itemObjectEntry) -> _getString(
					itemObjectEntry, "itemAction"
				).equals(
					ACTION_DELETE
				)
			).thenComparing(
				itemObjectEntry -> {
					int parentRank = _getParentRank(
						_getLong(itemObjectEntry, "objectDefinitionId"));

					// Publish: parents first. Delete: children first.

					return _getString(
						itemObjectEntry, "itemAction"
					).equals(
						ACTION_DELETE
					) ? parentRank : -parentRank;
				}
			));

		if (itemObjectEntries.isEmpty()) {
			throw new PortalException(
				"\"" + publication + "\" has no pending items");
		}

		// Name under which the publication is archived (items are renamed)

		SimpleDateFormat simpleDateFormat = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss");

		String archiveName =
			publication + " · " + simpleDateFormat.format(new Date());

		JSONArray resultsJSONArray = _jsonFactory.createJSONArray();
		List<String> missingLabels = new ArrayList<>();
		int errors = 0;
		int done = 0;

		for (ObjectEntry itemObjectEntry : itemObjectEntries) {
			String action = _getString(itemObjectEntry, "itemAction");
			String label = _getString(itemObjectEntry, "entryLabel");

			String status = STATUS_PUBLISHED;
			String message;

			try {
				if (action.equals(ACTION_DELETE)) {
					message = _deleteTarget(
						itemObjectEntry, sourceGroupId, targetGroupId);
				}
				else {
					ObjectEntry objectEntry =
						_objectEntryLocalService.fetchObjectEntry(
							_getLong(itemObjectEntry, "sourceEntryId"));

					if (objectEntry != null) {
						objectEntry = _getSourceObjectEntry(
							sourceGroupId, objectEntry.getObjectEntryId());
					}

					if (objectEntry == null) {
						missingLabels.add(label);

						status = STATUS_ERROR;
						message = "No longer exists in the source site";
					}
					else {
						message = _publish(userId, objectEntry, targetGroupId);
					}
				}

				if (status.equals(STATUS_PUBLISHED)) {
					done++;
				}
				else {
					errors++;
				}
			}
			catch (Exception exception) {
				_log.error(exception);

				status = STATUS_ERROR;

				// Expected errors keep their message; unexpected ones only
				// point to the log

				if ((exception instanceof PortalException) &&
					(exception.getMessage() != null)) {

					message = exception.getMessage();
				}
				else {
					message = "Unexpected error. See the server log.";
				}

				errors++;
			}

			_updateItem(
				userId, itemObjectEntry, archiveName, status, message,
				targetGroupId);

			resultsJSONArray.put(
				_jsonFactory.createJSONObject(
				).put(
					"action", action
				).put(
					"label", label
				).put(
					"message", message
				).put(
					"status", status
				));
		}

		StringBuilder sb = new StringBuilder();

		sb.append("Publication \"");
		sb.append(publication);
		sb.append("\": ");
		sb.append(done);
		sb.append(" published, ");
		sb.append(errors);
		sb.append(" with errors.");

		sb.append(" Archived as \"");
		sb.append(archiveName);
		sb.append("\".");

		if (!missingLabels.isEmpty()) {
			sb.append(" ");
			sb.append(missingLabels.size());
			sb.append(
				" entry(ies) deleted in the source but recorded in the " +
					"publication: ");
			sb.append(String.join(", ", missingLabels));
			sb.append(".");
		}

		return _message(
			sb.toString(), (errors == 0) ? "published" : "partial"
		).put(
			"archive", archiveName
		).put(
			"results", resultsJSONArray
		);
	}

	public JSONObject publishEntry(
			long userId, long sourceGroupId, long targetGroupId,
			long objectEntryId)
		throws PortalException {

		_checkTarget(sourceGroupId, targetGroupId);

		ObjectEntry objectEntry = _getSourceObjectEntry(
			sourceGroupId, objectEntryId);

		return _message(
			_publish(userId, objectEntry, targetGroupId), "published");
	}

	/**
	 * Deletes the entry in the target and in the source right away, without a
	 * publication (like "Publish now"). Removes its pending items.
	 */
	public JSONObject deleteEntry(
			long sourceGroupId, long targetGroupId, long objectEntryId)
		throws PortalException {

		_checkTarget(sourceGroupId, targetGroupId);

		ObjectEntry objectEntry = _getSourceObjectEntry(
			sourceGroupId, objectEntryId);

		String label = _getLabel(objectEntry);

		for (ObjectEntry itemObjectEntry :
				_getPendingItems(objectEntry.getCompanyId(), objectEntryId)) {

			_objectEntryLocalService.deleteObjectEntry(itemObjectEntry);
		}

		String message = "Did not exist in the target";

		ObjectEntry targetObjectEntry = _fetchTarget(
			objectEntry, targetGroupId);

		if (targetObjectEntry != null) {
			_objectEntryLocalService.deleteObjectEntry(targetObjectEntry);

			message = "Deleted in the target";
		}

		_objectEntryLocalService.deleteObjectEntry(objectEntry);

		return _message(
			message + " and in the source: " + label, "deleted");
	}

	/**
	 * Restores the source entry with the values of the target entry with the same
	 * ERC (discards the changes) and removes its pending items.
	 */
	public JSONObject restoreEntry(
			long userId, long sourceGroupId, long targetGroupId,
			long objectEntryId)
		throws PortalException {

		_checkTarget(sourceGroupId, targetGroupId);

		ObjectEntry objectEntry = _getSourceObjectEntry(
			sourceGroupId, objectEntryId);

		ObjectEntry targetObjectEntry = _fetchTarget(
			objectEntry, targetGroupId);

		if (targetObjectEntry == null) {
			throw new PortalException(
				"\"" + _getLabel(objectEntry) +
					"\" does not exist in the target: nothing to restore");
		}

		ObjectDefinition objectDefinition =
			_objectDefinitionLocalService.getObjectDefinition(
				objectEntry.getObjectDefinitionId());

		Map<String, Serializable> values;

		try {

			// Same calculation as when publishing, in reverse: relationships are
			// translated by ERC to the source entries

			values = _getTargetValues(
				objectDefinition, targetObjectEntry, sourceGroupId);
		}
		catch (PortalException portalException) {
			throw new PortalException(
				"Cannot restore: a related entry is missing in the " +
					"source. " + portalException.getMessage());
		}

		ObjectEntry restoredObjectEntry =
			_objectEntryLocalService.updateObjectEntry(
				userId, objectEntryId, objectEntry.getObjectEntryFolderId(),
				values,
				_createServiceContext(
					objectEntry.getCompanyId(), sourceGroupId, userId));

		_alignModifiedDate(
			restoredObjectEntry, targetObjectEntry.getModifiedDate());

		int removed = 0;

		for (ObjectEntry itemObjectEntry :
				_getPendingItems(objectEntry.getCompanyId(), objectEntryId)) {

			_objectEntryLocalService.deleteObjectEntry(itemObjectEntry);

			removed++;
		}

		return _message(
			"Restored from the target: " + _getLabel(objectEntry) +
				((removed > 0) ?
					" (" + removed + " pending mark(s) removed)" :
						""),
			"restored");
	}

	public JSONObject removeItem(long sourceGroupId, long itemId)
		throws PortalException {

		ObjectEntry itemObjectEntry = _objectEntryLocalService.getObjectEntry(
			itemId);

		// Only pending publication items of this site

		ObjectDefinition itemObjectDefinition = _getItemObjectDefinition(
			itemObjectEntry.getCompanyId());

		if ((itemObjectEntry.getObjectDefinitionId() !=
				itemObjectDefinition.getObjectDefinitionId()) ||
			(_getLong(itemObjectEntry, "sourceGroupId") != sourceGroupId) ||
			!_getString(itemObjectEntry, "itemStatus").equals(
				STATUS_PENDING)) {

			throw new PrincipalException(
				"The item is not a pending item of this site");
		}

		_objectEntryLocalService.deleteObjectEntry(itemObjectEntry);

		return _message(
			"Removed from \"" + _getString(itemObjectEntry, "publication") +
				"\": " + _getString(itemObjectEntry, "entryLabel"),
			"removed");
	}

	/**
	 * The target must be another site of the same instance, and the user must
	 * administer it too (or the instance).
	 */
	private void _checkTarget(long sourceGroupId, long targetGroupId)
		throws PortalException {

		if ((targetGroupId <= 0) || (targetGroupId == sourceGroupId)) {
			throw new PortalException("Select a target site");
		}

		Group sourceGroup = _groupLocalService.getGroup(sourceGroupId);
		Group targetGroup = _groupLocalService.getGroup(targetGroupId);

		if ((targetGroup.getCompanyId() != sourceGroup.getCompanyId()) ||
			!targetGroup.isSite() || !_isAdmin(targetGroup)) {

			throw new PrincipalException(
				"You must also be an administrator of the target site");
		}
	}

	/**
	 * Object definitions the module can publish: approved, site-scoped, of the
	 * same instance, and not the publication item object itself.
	 */
	private ObjectDefinition _getPublishableObjectDefinition(
			long companyId, long objectDefinitionId)
		throws PortalException {

		ObjectDefinition objectDefinition =
			_objectDefinitionLocalService.getObjectDefinition(
				objectDefinitionId);

		if ((objectDefinition.getCompanyId() != companyId) ||
			!objectDefinition.isApproved() ||
			!Objects.equals(
				objectDefinition.getScope(),
				ObjectDefinitionConstants.SCOPE_SITE) ||
			objectDefinition.isUnmodifiableSystemObject() ||
			(objectDefinitionId ==
				_getItemObjectDefinition(
					companyId
				).getObjectDefinitionId())) {

			throw new PrincipalException(
				"The object cannot be published with this module");
		}

		return objectDefinition;
	}

	/**
	 * Entry of the source site: it must belong to that site and to an object
	 * the module can publish.
	 */
	private ObjectEntry _getSourceObjectEntry(
			long sourceGroupId, long objectEntryId)
		throws PortalException {

		ObjectEntry objectEntry = _objectEntryLocalService.getObjectEntry(
			objectEntryId);

		if (objectEntry.getGroupId() != sourceGroupId) {
			throw new PrincipalException(
				"The entry does not belong to this site");
		}

		_getPublishableObjectDefinition(
			objectEntry.getCompanyId(), objectEntry.getObjectDefinitionId());

		return objectEntry;
	}

	private boolean _isAdmin(Group group) {
		PermissionChecker permissionChecker =
			PermissionThreadLocal.getPermissionChecker();

		if (permissionChecker == null) {
			return false;
		}

		return permissionChecker.isCompanyAdmin(group.getCompanyId()) ||
			permissionChecker.isGroupAdmin(group.getGroupId());
	}

	private ServiceContext _createServiceContext(
		long companyId, long groupId, long userId) {

		ServiceContext serviceContext = new ServiceContext();

		serviceContext.setAddGroupPermissions(true);
		serviceContext.setAddGuestPermissions(true);
		serviceContext.setCompanyId(companyId);
		serviceContext.setScopeGroupId(groupId);
		serviceContext.setUserId(userId);
		serviceContext.setWorkflowAction(WorkflowConstants.ACTION_PUBLISH);

		return serviceContext;
	}

	/**
	 * Deletes the entry in the target and, if it still exists, also in the
	 * source, so it does not show up again as "New" in the work view.
	 */
	private String _deleteTarget(
			ObjectEntry itemObjectEntry, long sourceGroupId, long targetGroupId)
		throws PortalException {

		// The item could have been edited outside the module: its object and
		// source entry are checked again

		_getPublishableObjectDefinition(
			itemObjectEntry.getCompanyId(),
			_getLong(itemObjectEntry, "objectDefinitionId"));

		ObjectEntry targetObjectEntry =
			_objectEntryLocalService.fetchObjectEntry(
				_getString(itemObjectEntry, "entryERC"), targetGroupId,
				_getLong(itemObjectEntry, "objectDefinitionId"));

		String message = "Did not exist in the target";

		if (targetObjectEntry != null) {
			_objectEntryLocalService.deleteObjectEntry(targetObjectEntry);

			message = "Deleted in the target";
		}

		ObjectEntry sourceObjectEntry =
			_objectEntryLocalService.fetchObjectEntry(
				_getLong(itemObjectEntry, "sourceEntryId"));

		if (sourceObjectEntry != null) {
			sourceObjectEntry = _getSourceObjectEntry(
				sourceGroupId, sourceObjectEntry.getObjectEntryId());

			_objectEntryLocalService.deleteObjectEntry(sourceObjectEntry);

			message += " and in the source";
		}

		return message;
	}

	private List<ObjectEntry> _getPendingItems(
		long companyId, long objectEntryId) {

		List<ObjectEntry> itemObjectEntries = new ArrayList<>();

		for (ObjectEntry itemObjectEntry : _getItems(companyId)) {
			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING) &&
				(_getLong(itemObjectEntry, "sourceEntryId") == objectEntryId)) {

				itemObjectEntries.add(itemObjectEntry);
			}
		}

		return itemObjectEntries;
	}

	private boolean _equals(Object value1, Object value2) {
		if ((value1 instanceof Map) && (value2 instanceof Map)) {
			return Objects.equals(
				String.valueOf(new TreeMap<>((Map<?, ?>)value1)),
				String.valueOf(new TreeMap<>((Map<?, ?>)value2)));
		}

		return Objects.equals(
			(value1 == null) ? "" : String.valueOf(value1),
			(value2 == null) ? "" : String.valueOf(value2));
	}

	private ObjectEntry _fetchPendingItem(
		long companyId, String publication, long objectEntryId) {

		for (ObjectEntry itemObjectEntry : _getItems(companyId)) {
			if (_getString(itemObjectEntry, "itemStatus").equals(
					STATUS_PENDING) &&
				_getString(itemObjectEntry, "publication").equals(
					publication.trim()) &&
				(_getLong(itemObjectEntry, "sourceEntryId") == objectEntryId)) {

				return itemObjectEntry;
			}
		}

		return null;
	}

	private ObjectEntry _fetchTarget(
		ObjectEntry objectEntry, long targetGroupId) {

		if ((targetGroupId <= 0) ||
			Validator.isNull(objectEntry.getExternalReferenceCode())) {

			return null;
		}

		return _objectEntryLocalService.fetchObjectEntry(
			objectEntry.getExternalReferenceCode(), targetGroupId,
			objectEntry.getObjectDefinitionId());
	}

	private List<String> _getChangedFields(
		ObjectDefinition objectDefinition, ObjectEntry objectEntry,
		ObjectEntry targetObjectEntry) {

		Map<String, Serializable> targetValues = new HashMap<>();

		try {
			targetValues = _getTargetValues(
				objectDefinition, objectEntry,
				targetObjectEntry.getGroupId());
		}
		catch (PortalException portalException) {

			// Relationship not published in the target yet: there are changes

			return List.of(portalException.getMessage());
		}

		Map<String, Serializable> currentValues = _getEditableValues(
			objectDefinition, targetObjectEntry.getValues());

		List<String> changedFields = new ArrayList<>();

		for (Map.Entry<String, Serializable> entry : targetValues.entrySet()) {
			if (!_equals(currentValues.get(entry.getKey()), entry.getValue())) {
				changedFields.add(entry.getKey());
			}
		}

		return changedFields;
	}

	private Map<String, Serializable> _getEditableValues(
		ObjectDefinition objectDefinition, Map<String, Serializable> values) {

		Map<String, Serializable> editableValues = new LinkedHashMap<>();

		for (ObjectField objectField :
				_objectFieldLocalService.getObjectFields(
					objectDefinition.getObjectDefinitionId())) {

			String name = objectField.getName();

			if (objectField.isSystem() ||
				Objects.equals(objectField.getBusinessType(), "Aggregation") ||
				Objects.equals(objectField.getBusinessType(), "Attachment") ||
				Objects.equals(objectField.getBusinessType(), "Formula") ||
				!values.containsKey(name)) {

				continue;
			}

			editableValues.put(name, values.get(name));
		}

		return editableValues;
	}

	private ObjectDefinition _getItemObjectDefinition(long companyId)
		throws PortalException {

		ObjectDefinition objectDefinition =
			_objectDefinitionLocalService.fetchObjectDefinition(
				companyId, _publicationItemObjectDefinitionName);

		if (objectDefinition == null) {
			throw new PortalException(
				"The publication item object does not exist: " +
					_publicationItemObjectDefinitionName);
		}

		return objectDefinition;
	}

	private List<ObjectEntry> _getItems(long companyId) {
		try {
			return _objectEntryLocalService.getObjectEntries(
				0,
				_getItemObjectDefinition(
					companyId
				).getObjectDefinitionId(),
				-1, -1);
		}
		catch (PortalException portalException) {
			_log.error(portalException);

			return new ArrayList<>();
		}
	}

	/**
	 * Name of a site by ID, also for sites that were deleted.
	 */
	private String _getGroupName(long groupId) {
		Group group = _groupLocalService.fetchGroup(groupId);

		if (group == null) {
			return "Deleted site (#" + groupId + ")";
		}

		return _getGroupName(group);
	}

	/**
	 * Site name as Liferay shows it. The default (Guest) site does not keep its
	 * own name: Liferay shows the instance name for it. Other sites use their
	 * descriptive name, then the stored name and finally the site key.
	 */
	private String _getGroupName(Group group) {
		Locale locale = LocaleUtil.fromLanguageId(group.getDefaultLanguageId());

		try {
			if (group.isGuest()) {
				Company company = _companyLocalService.getCompany(
					group.getCompanyId());

				return company.getName();
			}

			String name = group.getDescriptiveName(locale);

			if (Validator.isNotNull(name)) {
				return name;
			}
		}
		catch (PortalException portalException) {
			if (_log.isDebugEnabled()) {
				_log.debug(portalException);
			}
		}

		Map<Locale, String> nameMap = group.getNameMap();

		String name = nameMap.get(locale);

		if (Validator.isNull(name)) {
			name = group.getGroupKey();
		}

		return name;
	}


	private String _getLabel(ObjectEntry objectEntry) {
		try {
			String titleValue = objectEntry.getTitleValue();

			if (Validator.isNotNull(titleValue)) {
				return titleValue;
			}
		}
		catch (PortalException portalException) {
			if (_log.isDebugEnabled()) {
				_log.debug(portalException);
			}
		}

		return String.valueOf(objectEntry.getObjectEntryId());
	}

	/**
	 * Case-insensitive search in the entry's ERC and in the text of all its field
	 * values. An empty search matches every entry.
	 */
	private boolean _matches(ObjectEntry objectEntry, String search) {
		if (Validator.isNull(search)) {
			return true;
		}

		String text = search.trim(
		).toLowerCase(
			Locale.ROOT
		);

		if (_contains(objectEntry.getExternalReferenceCode(), text)) {
			return true;
		}

		for (Serializable value : objectEntry.getValues().values()) {
			if (_contains(value, text)) {
				return true;
			}
		}

		return false;
	}

	private boolean _contains(Object value, String text) {
		if (value == null) {
			return false;
		}

		return String.valueOf(
			value
		).toLowerCase(
			Locale.ROOT
		).contains(
			text
		);
	}

	private long _getLong(ObjectEntry objectEntry, String name) {
		Map<String, Serializable> values = objectEntry.getValues();

		return GetterUtil.getLong(values.get(name));
	}

	/**
	 * Number of one-to-many relationships where the definition is the parent (used
	 * to publish parents first).
	 */
	private int _getParentRank(long objectDefinitionId) {
		int rank = 0;

		for (ObjectRelationship objectRelationship :
				_objectRelationshipLocalService.getObjectRelationships(
					objectDefinitionId)) {

			if ((objectRelationship.getObjectDefinitionId1() ==
					objectDefinitionId) &&
				Objects.equals(
					objectRelationship.getType(),
					ObjectRelationshipConstants.TYPE_ONE_TO_MANY)) {

				rank++;
			}
		}

		return rank;
	}

	private List<ObjectEntry> _getPublicationItems(
		long companyId, long sourceGroupId, String publication) {

		List<ObjectEntry> itemObjectEntries = new ArrayList<>();

		for (ObjectEntry itemObjectEntry : _getItems(companyId)) {
			if ((_getLong(itemObjectEntry, "sourceGroupId") ==
					sourceGroupId) &&
				_getString(itemObjectEntry, "publication").equals(
					publication)) {

				itemObjectEntries.add(itemObjectEntry);
			}
		}

		return itemObjectEntries;
	}

	/**
	 * URL of the entry's edit form in the site administration of the source
	 * site (object entries portlet of the definition). The form looks the
	 * entry up by external reference code in the site of the URL; with no ERC
	 * it opens an empty form.
	 */
	private String _getEditURL(
		Group group, ObjectDefinition objectDefinition, ObjectEntry objectEntry) {

		if (group == null) {
			return null;
		}

		String portletId = objectDefinition.getPortletId();

		return _portal.getPathFriendlyURLPrivateGroup() +
			group.getFriendlyURL() + "/~/control_panel/manage?p_p_id=" +
				portletId + "&p_p_lifecycle=0&_" + portletId +
					"_mvcRenderCommandName=%2Fobject_entries%2Fedit_object_entry" +
						"&_" + portletId + "_externalReferenceCode=" +
							URLEncoder.encode(
								objectEntry.getExternalReferenceCode(),
								StandardCharsets.UTF_8);
	}

	private String _getPublicURL(
		Group group, ObjectDefinition objectDefinition,
		ObjectEntry objectEntry, Locale locale) {

		if ((group == null) || (objectEntry == null)) {
			return null;
		}

		String urlTitle = (locale == null) ? null :
			objectEntry.getURLTitle(locale);

		if (Validator.isNull(urlTitle)) {
			Map<String, String> urlTitleMap = objectEntry.getURLTitleMap();

			if (!urlTitleMap.isEmpty()) {
				urlTitle = urlTitleMap.values(
				).iterator(
				).next();
			}
		}

		if (Validator.isNull(objectDefinition.getFriendlyURLSeparator())) {
			return null;
		}

		if (Validator.isNull(urlTitle)) {

			// Entries without a friendly URL entry (for example drafts or
			// imported entries) are resolved by the display page through
			// their primary key

			urlTitle = String.valueOf(objectEntry.getObjectEntryId());
		}

		return _portal.getPathFriendlyURLPublic() + group.getFriendlyURL() +
			"/" + objectDefinition.getFriendlyURLSeparator() + "/" + urlTitle;
	}

	/**
	 * State compared with the target. Fast check by date: if the source was not
	 * modified after the target (publishing updates the target and gives it a later
	 * date), it is unchanged and no values are read. Only when the source is newer
	 * are the values compared, so an entry saved without changes is not reported
	 * as modified.
	 */
	private String _getTargetState(
		ObjectDefinition objectDefinition, ObjectEntry objectEntry,
		ObjectEntry targetObjectEntry) {

		if (targetObjectEntry == null) {
			return "new";
		}

		Date modifiedDate = objectEntry.getModifiedDate();
		Date targetModifiedDate = targetObjectEntry.getModifiedDate();

		if ((modifiedDate == null) || (targetModifiedDate == null)) {
			return _getChangedFields(
				objectDefinition, objectEntry, targetObjectEntry
			).isEmpty() ? "same" : "changed";
		}

		long difference =
			modifiedDate.getTime() - targetModifiedDate.getTime();

		// Same date (publishing and restoring align both dates): unchanged
		// without reading values. Some databases store whole seconds.

		if (Math.abs(difference) < 1000) {
			return "same";
		}

		if (_getChangedFields(
				objectDefinition, objectEntry, targetObjectEntry
			).isEmpty()) {

			return "same";
		}

		// Newer in the source: pending to publish. Newer in the target: it
		// was edited there (for example, an urgent change in production)

		return (difference > 0) ? "changed" : "targetChanged";
	}

	/**
	 * Sets the modified date of an entry without changing anything else.
	 */
	private void _alignModifiedDate(ObjectEntry objectEntry, Date date) {
		if ((objectEntry == null) || (date == null)) {
			return;
		}

		ObjectEntry freshObjectEntry = _objectEntryLocalService.fetchObjectEntry(
			objectEntry.getObjectEntryId());

		if (freshObjectEntry == null) {
			return;
		}

		freshObjectEntry.setModifiedDate(date);

		_objectEntryLocalService.updateObjectEntry(freshObjectEntry);
	}


	private String _getString(ObjectEntry objectEntry, String name) {
		Map<String, Serializable> values = objectEntry.getValues();

		return GetterUtil.getString(values.get(name));
	}

	/**
	 * Source values as they must be stored in the target: relationships are
	 * translated to the id of the entry with the same ERC in the target.
	 */
	private Map<String, Serializable> _getTargetValues(
			ObjectDefinition objectDefinition, ObjectEntry objectEntry,
			long targetGroupId)
		throws PortalException {

		Map<String, Serializable> values = _getEditableValues(
			objectDefinition, objectEntry.getValues());

		for (ObjectField objectField :
				_objectFieldLocalService.getObjectFields(
					objectDefinition.getObjectDefinitionId())) {

			if (Validator.isNull(objectField.getRelationshipType()) ||
				!values.containsKey(objectField.getName())) {

				continue;
			}

			long relatedId = GetterUtil.getLong(
				values.get(objectField.getName()));

			if (relatedId <= 0) {
				continue;
			}

			ObjectEntry relatedObjectEntry =
				_objectEntryLocalService.fetchObjectEntry(relatedId);

			ObjectEntry targetRelatedObjectEntry = null;

			if (relatedObjectEntry != null) {
				targetRelatedObjectEntry = _fetchTarget(
					relatedObjectEntry, targetGroupId);
			}

			if (targetRelatedObjectEntry == null) {
				throw new PortalException(
					"Publish \"" +
						((relatedObjectEntry == null) ? relatedId :
							_getLabel(relatedObjectEntry)) +
								"\" first (relationship " + objectField.getName() + ")");
			}

			values.put(
				objectField.getName(),
				targetRelatedObjectEntry.getObjectEntryId());
		}

		return values;
	}

	private JSONObject _message(String message, String result) {
		return _jsonFactory.createJSONObject(
		).put(
			"message", message
		).put(
			"result", result
		);
	}

	private String _publish(
			long userId, ObjectEntry objectEntry, long targetGroupId)
		throws PortalException {

		if (!objectEntry.isApproved()) {
			throw new PortalException(
				"\"" + _getLabel(objectEntry) + "\" is not approved");
		}

		ObjectDefinition objectDefinition =
			_objectDefinitionLocalService.getObjectDefinition(
				objectEntry.getObjectDefinitionId());

		Map<String, Serializable> values = _getTargetValues(
			objectDefinition, objectEntry, targetGroupId);

		ServiceContext serviceContext = _createServiceContext(
			objectEntry.getCompanyId(), targetGroupId, userId);

		ObjectEntry targetObjectEntry = _fetchTarget(
			objectEntry, targetGroupId);

		if ((targetObjectEntry != null) &&
			"targetChanged".equals(
				_getTargetState(
					objectDefinition, objectEntry, targetObjectEntry))) {

			throw new PortalException(
				"\"" + _getLabel(objectEntry) +
					"\" was changed in the target after its last publication. " +
						"Restore it from the target first.");
		}

		String message = "Updated in the target";

		if (targetObjectEntry != null) {
			targetObjectEntry = _objectEntryLocalService.updateObjectEntry(
				userId, targetObjectEntry.getObjectEntryId(),
				targetObjectEntry.getObjectEntryFolderId(), values,
				serviceContext);
		}
		else {
			values.put(
				"externalReferenceCode",
				objectEntry.getExternalReferenceCode());

			targetObjectEntry = _objectEntryLocalService.addObjectEntry(
				targetGroupId, userId, objectEntry.getObjectDefinitionId(), 0,
				objectEntry.getDefaultLanguageId(), values, serviceContext);

			message = "Created in the target";
		}

		// Both entries keep the same modified date, so the next comparison is
		// a date check and later edits on either side are detected

		_alignModifiedDate(targetObjectEntry, objectEntry.getModifiedDate());

		_removeOwnerPermissions(objectDefinition, objectEntry);

		return message;
	}

	/**
	 * Once published, the source entry (site B) can no longer be deleted, nor can
	 * its permissions be changed, just by being its owner: DELETE and PERMISSIONS
	 * are removed from the Owner role (VIEW and UPDATE are kept). This way its
	 * creator cannot skip the procedure: to delete it, it must be marked for
	 * deletion and published. Publishing is not affected (local services, no
	 * permission checks).
	 */
	private void _removeOwnerPermissions(
			ObjectDefinition objectDefinition, ObjectEntry objectEntry)
		throws PortalException {

		Role role = _roleLocalService.getRole(
			objectEntry.getCompanyId(), RoleConstants.OWNER);

		String primKey = String.valueOf(objectEntry.getObjectEntryId());

		// Individual permissions of the entry, looked up by resource name (the
		// definition's class name) and primary key, and filtered by the Owner
		// role

		Set<String> names = new LinkedHashSet<>();

		names.add(objectDefinition.getClassName());
		names.add(objectEntry.getModelClassName());

		int removed = 0;

		for (String name : names) {
			for (ResourcePermission resourcePermission :
					_resourcePermissionLocalService.getResourcePermissions(
						objectEntry.getCompanyId(), name,
						ResourceConstants.SCOPE_INDIVIDUAL, primKey)) {

				if (resourcePermission.getRoleId() != role.getRoleId()) {
					continue;
				}

				// Removed through the service so that Liferay also refreshes its
				// permission cache

				for (String actionId :
						new String[] {
							ActionKeys.DELETE, ActionKeys.PERMISSIONS
						}) {

					if (resourcePermission.hasActionId(actionId)) {
						_resourcePermissionLocalService.removeResourcePermission(
							objectEntry.getCompanyId(), name,
							ResourceConstants.SCOPE_INDIVIDUAL, primKey,
							role.getRoleId(), actionId);

						removed++;
					}
				}
			}
		}

		if ((removed == 0) && _log.isDebugEnabled()) {
			_log.debug(
				"No Owner permissions to remove for entry " + primKey + " (" +
					names + ")");
		}
	}

	private JSONObject _toItemJSONObject(ObjectEntry itemObjectEntry) {
		Date modifiedDate = itemObjectEntry.getModifiedDate();

		return _jsonFactory.createJSONObject(
		).put(
			"action", _getString(itemObjectEntry, "itemAction")
		).put(
			"erc", _getString(itemObjectEntry, "entryERC")
		).put(
			"id", itemObjectEntry.getObjectEntryId()
		).put(
			"label", _getString(itemObjectEntry, "entryLabel")
		).put(
			"message", _getString(itemObjectEntry, "message")
		).put(
			"modifiedDate", (modifiedDate == null) ? 0 : modifiedDate.getTime()
		).put(
			"publication", _getString(itemObjectEntry, "publication")
		).put(
			"sourceEntryId", _getLong(itemObjectEntry, "sourceEntryId")
		).put(
			"objectDefinitionId",
			_getLong(itemObjectEntry, "objectDefinitionId")
		).put(
			"status", _getString(itemObjectEntry, "itemStatus")
		).put(
			"targetGroupId", _getLong(itemObjectEntry, "targetGroupId")
		).put(
			"userName", itemObjectEntry.getUserName()
		);
	}

	private JSONObject _toJSONObject(
		ObjectDefinition objectDefinition, ObjectEntry objectEntry,
		ObjectEntry targetObjectEntry, String targetState,
		List<ObjectEntry> itemObjectEntries, long sourceGroupId,
		long targetGroupId, Locale locale) {

		Date modifiedDate = objectEntry.getModifiedDate();

		JSONArray itemsJSONArray = _jsonFactory.createJSONArray();

		for (ObjectEntry itemObjectEntry : itemObjectEntries) {
			itemsJSONArray.put(_toItemJSONObject(itemObjectEntry));
		}

		return _jsonFactory.createJSONObject(
		).put(
			"externalReferenceCode", objectEntry.getExternalReferenceCode()
		).put(
			"id", objectEntry.getObjectEntryId()
		).put(
			"items", itemsJSONArray
		).put(
			"label", _getLabel(objectEntry)
		).put(
			"modifiedDate", (modifiedDate == null) ? 0 : modifiedDate.getTime()
		).put(
			"editURL",
			_getEditURL(
				_groupLocalService.fetchGroup(sourceGroupId), objectDefinition,
				objectEntry)
		).put(
			"sourceURL",
			_getPublicURL(
				_groupLocalService.fetchGroup(sourceGroupId), objectDefinition,
				objectEntry, locale)
		).put(
			"status", objectEntry.getStatus()
		).put(
			"statusLabel",
			WorkflowConstants.getStatusLabel(objectEntry.getStatus())
		).put(
			"targetState", targetState
		).put(
			"targetURL",
			_getPublicURL(
				_groupLocalService.fetchGroup(targetGroupId), objectDefinition,
				targetObjectEntry, locale)
		);
	}

	private void _updateItem(
			long userId, ObjectEntry itemObjectEntry, String publication,
			String status, String message, long targetGroupId)
		throws PortalException {

		Map<String, Serializable> values = new HashMap<>();

		values.put("publication", publication);
		values.put("itemStatus", status);
		values.put("message", message);
		values.put("targetGroupId", targetGroupId);

		_objectEntryLocalService.partialUpdateObjectEntry(
			userId, itemObjectEntry.getObjectEntryId(), 0, values,
			_createServiceContext(itemObjectEntry.getCompanyId(), 0, userId));
	}

	private static final Log _log = LogFactoryUtil.getLog(SitePublisher.class);

	@Reference
	private CompanyLocalService _companyLocalService;

	@Reference
	private GroupLocalService _groupLocalService;

	@Reference
	private JSONFactory _jsonFactory;

	@Reference
	private ObjectDefinitionLocalService _objectDefinitionLocalService;

	@Reference
	private ObjectEntryLocalService _objectEntryLocalService;

	@Reference
	private ObjectFieldLocalService _objectFieldLocalService;

	@Reference
	private ObjectRelationshipLocalService _objectRelationshipLocalService;

	@Reference
	private Portal _portal;

	private volatile String _publicationItemObjectDefinitionName =
		SiteObjectPublisherPortletKeys.PUBLICATION_ITEM_OBJECT_DEFINITION_NAME;

	@Reference
	private ResourcePermissionLocalService _resourcePermissionLocalService;

	@Reference
	private RoleLocalService _roleLocalService;

}
