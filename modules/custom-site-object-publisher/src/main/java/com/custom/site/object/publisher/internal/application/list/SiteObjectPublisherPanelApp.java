package com.custom.site.object.publisher.internal.application.list;

import com.custom.site.object.publisher.constants.SiteObjectPublisherPortletKeys;

import com.liferay.application.list.BasePanelApp;
import com.liferay.application.list.PanelApp;
import com.liferay.application.list.constants.PanelCategoryKeys;
import com.liferay.portal.kernel.model.Portlet;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Entry in Site Administration > Publishing.
 *
 * @author Custom Site Object Publisher
 */
@Component(
	property = {
		"panel.app.order:Integer=960",
		"panel.category.key=" + PanelCategoryKeys.SITE_ADMINISTRATION_PUBLISHING
	},
	service = PanelApp.class
)
public class SiteObjectPublisherPanelApp extends BasePanelApp {

	@Override
	public Portlet getPortlet() {
		return _portlet;
	}

	@Override
	public String getPortletId() {
		return SiteObjectPublisherPortletKeys.SITE_OBJECT_PUBLISHER;
	}

	@Reference(
		target = "(jakarta.portlet.name=" + SiteObjectPublisherPortletKeys.SITE_OBJECT_PUBLISHER + ")"
	)
	private Portlet _portlet;

}
