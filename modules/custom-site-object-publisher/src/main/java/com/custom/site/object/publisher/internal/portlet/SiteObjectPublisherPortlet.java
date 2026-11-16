package com.custom.site.object.publisher.internal.portlet;

import com.custom.site.object.publisher.constants.SiteObjectPublisherPortletKeys;

import com.liferay.portal.kernel.theme.ThemeDisplay;
import com.liferay.portal.kernel.util.WebKeys;

import jakarta.portlet.GenericPortlet;
import jakarta.portlet.Portlet;
import jakarta.portlet.RenderRequest;
import jakarta.portlet.RenderResponse;

import java.io.IOException;
import java.io.PrintWriter;

import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Component;

/**
 * Container portlet: it only renders the mount point and loads the assets. All
 * the logic is in /js/site-object-publisher.js, which calls the REST API in
 * /o/site-object-publisher (client extension style). The assets are loaded
 * with the bundle's last-modified time as version, so every deployment is
 * picked up by the browser instead of a cached copy.
 *
 * @author Custom Site Object Publisher
 */
@Component(
	property = {
		"com.liferay.portlet.add-default-resource=true",
		"com.liferay.portlet.css-class-wrapper=site-object-publisher-portlet",
		"com.liferay.portlet.display-category=category.tools",
		"com.liferay.portlet.instanceable=false",
		"jakarta.portlet.display-name=Site Object Publisher",
		"jakarta.portlet.expiration-cache=0",
		"jakarta.portlet.name=" + SiteObjectPublisherPortletKeys.SITE_OBJECT_PUBLISHER,
		"jakarta.portlet.resource-bundle=content.Language",
		"jakarta.portlet.security-role-ref=administrator",
		"jakarta.portlet.version=4.0"
	},
	service = Portlet.class
)
public class SiteObjectPublisherPortlet extends GenericPortlet {

	/**
	 * Mode of the JS application: "work" (work view and active publications) or
	 * "history" (past publications).
	 */
	protected String getMode() {
		return "work";
	}

	@Override
	protected void doView(
			RenderRequest renderRequest, RenderResponse renderResponse)
		throws IOException {

		ThemeDisplay themeDisplay = (ThemeDisplay)renderRequest.getAttribute(
			WebKeys.THEME_DISPLAY);

		renderResponse.setContentType("text/html");

		PrintWriter printWriter = renderResponse.getWriter();

		String contextPath = renderRequest.getContextPath();
		String version = String.valueOf(
			FrameworkUtil.getBundle(
				SiteObjectPublisherPortlet.class
			).getLastModified());

		printWriter.write(
			"<script>(function(){var v='" + version + "',c='" + contextPath +
				"';if(window['sopAssets'+v]){return;}window['sopAssets'+v]=" +
					"true;var l=document.createElement('link');l.rel=" +
						"'stylesheet';l.href=c+'/css/site-object-publisher.css" +
							"?v='+v;document.head.appendChild(l);var s=" +
								"document.createElement('script');s.src=c+" +
									"'/js/site-object-publisher.js?v='+v;" +
										"document.body.appendChild(s);})();" +
											"</script>");

		printWriter.write(
			"<div class=\"site-object-publisher\" data-mode=\"" +
				getMode() + "\" data-group-id=\"" +
				themeDisplay.getScopeGroupId() + "\" data-namespace=\"" +
					renderResponse.getNamespace() + "\">" +
						"<span aria-hidden=\"true\" class=\"loading-animation\">" +
							"</span></div>");
	}

}
