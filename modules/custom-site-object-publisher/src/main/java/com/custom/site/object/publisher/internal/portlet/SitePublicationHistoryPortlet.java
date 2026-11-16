package com.custom.site.object.publisher.internal.portlet;

import com.custom.site.object.publisher.constants.SiteObjectPublisherPortletKeys;

import jakarta.portlet.Portlet;

import org.osgi.service.component.annotations.Component;

/**
 * Past publications (archived when they are published). Same JS as the work
 * portlet, in "history" mode.
 *
 * @author Custom Site Object Publisher
 */
@Component(
	property = {
		"com.liferay.portlet.add-default-resource=true",
		"com.liferay.portlet.css-class-wrapper=site-publication-history-portlet",
		"com.liferay.portlet.display-category=category.tools",
		"com.liferay.portlet.instanceable=false",
		"jakarta.portlet.display-name=Past Publications",
		"jakarta.portlet.expiration-cache=0",
		"jakarta.portlet.name=" + SiteObjectPublisherPortletKeys.SITE_PUBLICATION_HISTORY,
		"jakarta.portlet.resource-bundle=content.Language",
		"jakarta.portlet.security-role-ref=administrator",
		"jakarta.portlet.version=4.0"
	},
	service = Portlet.class
)
public class SitePublicationHistoryPortlet
	extends SiteObjectPublisherPortlet {

	@Override
	protected String getMode() {
		return "history";
	}

}
