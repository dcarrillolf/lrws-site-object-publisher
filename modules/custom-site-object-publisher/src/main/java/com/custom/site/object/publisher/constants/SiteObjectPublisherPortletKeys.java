package com.custom.site.object.publisher.constants;

/**
 * @author Custom Site Object Publisher
 */
public class SiteObjectPublisherPortletKeys {

	public static final String SITE_OBJECT_PUBLISHER =
		"com_custom_site_object_publisher_web_SiteObjectPublisherPortlet";

	public static final String SITE_PUBLICATION_HISTORY =
		"com_custom_site_object_publisher_web_SitePublicationHistoryPortlet";

	/**
	 * Default name of the publication item object (company scoped) that stores the
	 * entries marked in each publication. It can be changed with the OSGi
	 * configuration of SitePublisher (publicationItemObjectDefinitionName).
	 */
	public static final String PUBLICATION_ITEM_OBJECT_DEFINITION_NAME =
		"C_PublicationItem";

}
