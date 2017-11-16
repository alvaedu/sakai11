package org.sakaiproject.gradebookng.rest;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.entitybroker.EntityReference;
import org.sakaiproject.entitybroker.EntityView;
import org.sakaiproject.entitybroker.entityprovider.annotations.EntityCustomAction;
import org.sakaiproject.entitybroker.entityprovider.capabilities.ActionsExecutable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.AutoRegisterEntityProvider;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Describeable;
import org.sakaiproject.entitybroker.entityprovider.capabilities.Outputable;
import org.sakaiproject.entitybroker.entityprovider.extension.Formats;
import org.sakaiproject.entitybroker.util.AbstractEntityProvider;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.InUseException;
import org.sakaiproject.exception.PermissionException;
import org.sakaiproject.gradebookng.business.GbRole;
import org.sakaiproject.gradebookng.business.GradebookNgBusinessService;
import org.sakaiproject.gradebookng.business.exception.GbAccessDeniedException;
import org.sakaiproject.gradebookng.business.model.GbGradeCell;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.SessionManager;

import lombok.Setter;
import org.sakaiproject.user.api.Preferences;
import org.sakaiproject.user.api.PreferencesEdit;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserEdit;
import org.sakaiproject.user.cover.PreferencesService;

/**
 * This entity provider is to support some of the Javascript front end pieces. It never was built to support third party access, and never
 * will support that use case.
 *
 * The data you need for Gradebook integrations should already be available in the standard gradebook entityprovider
 *
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
public class GradebookNgEntityProvider extends AbstractEntityProvider implements
		AutoRegisterEntityProvider, ActionsExecutable,
		Outputable, Describeable {

	private static final String PREF_GRADEBOOK_PROPERTY = "sakai:gradebookng";
	private static final String PREF_GRADEBOOK_WELCOME_DISMISSED_PROPERTY = "gradebookng-help-popup-dismissed";
	private static final String PREF_GRADEBOOK_WELCOME_DISMISSED_VALUE = "true";

	@Override
	public String[] getHandledOutputFormats() {
		return new String[] { Formats.JSON };
	}

	@Override
	public String getEntityPrefix() {
		return "gbng";
	}

	/**
	 * Update the order of an assignment in the gradebook This is a per site setting.
	 *
	 * @param ref
	 * @param params map, must include: siteId assignmentId new order
	 *
	 *            an assignmentorder object will be created and saved as a list in the XML property 'gbng_assignment_order'
	 */
	@EntityCustomAction(action = "assignment-order", viewKey = EntityView.VIEW_NEW)
	public void updateAssignmentOrder(final EntityReference ref, final Map<String, Object> params) {

		// get params
		final String siteId = (String) params.get("siteId");
		final long assignmentId = NumberUtils.toLong((String) params.get("assignmentId"));
		final int order = NumberUtils.toInt((String) params.get("order"));

		// check params supplied are valid
		if (StringUtils.isBlank(siteId) || assignmentId == 0 || order < 0) {
			throw new IllegalArgumentException(
					"Request data was missing / invalid");
		}
		checkValidSite(siteId);

		// check instructor
		checkInstructor(siteId);

		// update the order
		this.businessService.updateAssignmentOrder(siteId, assignmentId, order);
	}

	/**
	 * Endpoint for getting the list of cells that have been edited. TODO enhance to accept a timestamp so we can filter the list This is
	 * designed to be polled on a regular basis so must be lightweight
	 *
	 * @param view
	 * @return
	 */
	@EntityCustomAction(action = "isotheruserediting", viewKey = EntityView.VIEW_LIST)
	public List<GbGradeCell> isAnotherUserEditing(final EntityView view, final Map<String, Object> params) {

		// get siteId
		final String siteId = view.getPathSegment(2);

		// check siteId supplied
		if (StringUtils.isBlank(siteId)) {
			throw new IllegalArgumentException(
					"Site ID must be set in order to access GBNG data.");
		}
		checkValidSite(siteId);

		// check instructor or TA
		checkInstructorOrTA(siteId);

		if (!params.containsKey("since")) {
			throw new IllegalArgumentException(
				"Since timestamp (in milliseconds) must be set in order to access GBNG data.");
		}

		final long millis = NumberUtils.toLong((String) params.get("since"));
		final Date since = new Date(millis);

		return this.businessService.getEditingNotifications(siteId, since);
	}

	@EntityCustomAction(action = "categorized-assignment-order", viewKey = EntityView.VIEW_NEW)
	public void updateCategorizedAssignmentOrder(final EntityReference ref, final Map<String, Object> params) {

		// get params
		final String siteId = (String) params.get("siteId");
		final long assignmentId = NumberUtils.toLong((String) params.get("assignmentId"));
		final int order = NumberUtils.toInt((String) params.get("order"));

		// check params supplied are valid
		if (StringUtils.isBlank(siteId) || assignmentId == 0 || order < 0) {
			throw new IllegalArgumentException(
					"Request data was missing / invalid");
		}
		checkValidSite(siteId);

		// check instructor
		checkInstructor(siteId);

		// update the order
		try {
			this.businessService.updateAssignmentCategorizedOrder(siteId, assignmentId, order);
		} catch (final IdUnusedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (final PermissionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@EntityCustomAction(action = "ping", viewKey = EntityView.VIEW_LIST)
	public String ping(final EntityView view) {
		return "pong";
	}


	@EntityCustomAction(action = "comments", viewKey = EntityView.VIEW_LIST)
	public String getComments(final EntityView view, final Map<String, Object> params) {
		// get params
		final String siteId = (String) params.get("siteId");
		final long assignmentId = NumberUtils.toLong((String) params.get("assignmentId"));
		final String studentUuid = (String) params.get("studentUuid");

		// check params supplied are valid
		if (StringUtils.isBlank(siteId) || assignmentId == 0 || StringUtils.isBlank(studentUuid)) {
			throw new IllegalArgumentException(
				"Request data was missing / invalid");
		}

		checkValidSite(siteId);
		checkInstructorOrTA(siteId);

		return this.businessService.getAssignmentGradeComment(siteId, assignmentId, studentUuid);
	}


	/**
	 * Helper to check if the user is an instructor. Throws IllegalArgumentException if not. We don't currently need the value that this
	 * produces so we don't return it.
	 *
	 * @param siteId
	 * @return
	 * @throws SecurityException if error in auth/role
	 */
	private void checkInstructor(final String siteId)  {

		final String currentUserId = getCurrentUserId();

		if (StringUtils.isBlank(currentUserId)) {
			throw new SecurityException("You must be logged in to access GBNG data");
		}

		final GbRole role = getUserRole(siteId);

		if (role != GbRole.INSTRUCTOR) {
			throw new SecurityException("You do not have instructor-type permissions in this site.");
		}
	}

	/**
	 * Helper to check if the user is an instructor or a TA. Throws IllegalArgumentException if not. We don't currently need the value that
	 * this produces so we don't return it.
	 *
	 * @param siteId
	 * @return
	 * @throws SecurityException
	 */
	private void checkInstructorOrTA(final String siteId) {

		final String currentUserId = getCurrentUserId();

		if (StringUtils.isBlank(currentUserId)) {
			throw new SecurityException("You must be logged in to access GBNG data");
		}

		final GbRole role = getUserRole(siteId);

		if (role != GbRole.INSTRUCTOR && role != GbRole.TA) {
			throw new SecurityException("You do not have instructor or TA-type permissions in this site.");
		}
	}

	/**
	 * Helper to get current user id
	 *
	 * @return
	 */
	private String getCurrentUserId() {
		return this.sessionManager.getCurrentSessionUserId();
	}

	/**
	 * Helper to check a site ID is valid. Throws IllegalArgumentException if not. We don't currently need the site that this produces so we
	 * don't return it.
	 *
	 * @param siteId
	 */
	private void checkValidSite(final String siteId) {
		try {
			this.siteService.getSite(siteId);
		} catch (final IdUnusedException e) {
			throw new IllegalArgumentException("Invalid site id");
		}
	}

	/**
	 * Get role for current user in given site
	 * @param siteId
	 * @return
	 */
	private GbRole getUserRole(final String siteId) {
		GbRole role;
		try {
			role = this.businessService.getUserRole(siteId);
		} catch (final GbAccessDeniedException e) {
			throw new SecurityException("Your role could not be checked properly. This may be a role configuration issue in this site.");
		}
		return role;
	}

	@Setter
	private SiteService siteService;

	@Setter
	private SessionManager sessionManager;

	@Setter
	private SecurityService securityService;

	@Setter
	private GradebookNgBusinessService businessService;


	// CLASSES-2716 endpoints to support a instructional popover
	@EntityCustomAction(action = "show-help-popup", viewKey = EntityView.VIEW_LIST)
	public String shouldShowHelpPopup(final EntityView view, final Map<String, Object> params) {
		final String siteId = (String) params.get("siteId");

		if (StringUtils.isBlank(siteId)) {
			throw new IllegalArgumentException("Site ID must be set");
		}

		checkValidSite(siteId);
		checkInstructorOrTA(siteId);

		User user = this.businessService.getCurrentUser();

		// nothing for the admin user please
		if ("admin".equals(user.getId())) {
			return "false";
		}

		Preferences prefs = PreferencesService.getPreferences(user.getId());
		ResourceProperties properties = prefs.getProperties(PREF_GRADEBOOK_PROPERTY);

		String dismissed = (String) properties.get(PREF_GRADEBOOK_WELCOME_DISMISSED_PROPERTY);
		if (PREF_GRADEBOOK_WELCOME_DISMISSED_VALUE.equals(dismissed)) {
			return "false";
		} else {
			return "true";
		}
	}

	@EntityCustomAction(action = "dismiss-help-popup", viewKey = EntityView.VIEW_NEW)
	public void dismissHelpPopup(final EntityReference ref, final Map<String, Object> params) {
		final String siteId = (String) params.get("siteId");

		if (StringUtils.isBlank(siteId)) {
			throw new IllegalArgumentException("Site ID must be set");
		}

		checkValidSite(siteId);
		checkInstructorOrTA(siteId);

		try {
			User user = this.businessService.getCurrentUser();

			// we don't do this for the admin user
			if (!"admin".equals(user.getId())) {
				PreferencesEdit edit = getOrCreatePreferences(user.getId());
				ResourcePropertiesEdit properties = edit.getPropertiesEdit(PREF_GRADEBOOK_PROPERTY);
				properties.addProperty(PREF_GRADEBOOK_WELCOME_DISMISSED_PROPERTY, PREF_GRADEBOOK_WELCOME_DISMISSED_VALUE);
				PreferencesService.commit(edit);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Unable to set popup dismissal for user");
		}
	}

	private static PreferencesEdit getOrCreatePreferences(String userId) throws PermissionException, InUseException {
		try {
			return PreferencesService.edit(userId);
		} catch (IdUnusedException e) {
			try {
				return PreferencesService.add(userId);
			} catch (Exception e2) {
				throw new RuntimeException("Unable to get preferences for user: " + userId);
			}
		}
	}
}