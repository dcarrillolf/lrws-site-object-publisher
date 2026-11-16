# Site Object Publisher

Liferay DXP 2026.Q1 module to **preview and publish object entries from one site to another**, without Publications, Staging, workflow or drafts.

- **Source site (B):** working copy where editors change entries. It is also the preview.
- **Target site (A):** the published site.
- Entries are matched by **external reference code (ERC)**, from approved to approved.

> [!WARNING]
> Proof of concept. Test it with your own objects before using it in production.

## Features

- Two portlets in **Site Administration > Publishing**:
  - **Site Object Publisher:** compare and publish.
  - **Past Publications:** history of the publications the site has sent (as source) or received (as target), with the other site and the date. Click a row to see its items and the result of each one.
- Nothing is listed until a **target site** is selected.
- Entry list per site-scoped object, 50 per page, with its state against the target:
  - **New:** not in the target yet.
  - **Modified:** changed in the source, pending to publish.
  - **Changed in target:** edited directly in the target (for example, a production hotfix). It cannot be published until it is restored.
  - **Unchanged.**
- **Filters:** *Only with changes* and *Search* (text in any field or in the ERC).
- **Publications:** named groups of entries, filled one by one or with multi-select, and published together.
  - Parents first, then children, then deletions.
  - Published publications are archived as `name · date`, so the name can be reused.
- **Per-entry actions:**
  - include in a publication or mark for deletion;
  - publish now or delete now;
  - restore from target;
  - view in target or in this site;
  - edit (opens the entry form in a new tab).
- **Create or update:** if the target has an entry with the same ERC it is updated; otherwise it is created with that ERC.
- **Relationships:** one-to-many relationships are translated through the ERC of the related entry.
- **Deletions:** a deletion mark wins over pending changes, and publishing it removes the entry from both sites.
- **Owner permissions:** once an entry is published, the *Owner* role loses *Delete* and *Permissions* on it in the source site. *View* and *Update* are kept, so the creator can still edit it but can only delete it through a publication.
- **History survives site deletion:** publication items are stored in a company-scoped object, so if the source site is deleted its publications are still listed in the target as *Received from Deleted site (#id)*. A past publication can be deleted from its source site, or from its target once the source is gone.
- **Fast comparison:** publish and restore give both entries the same modified date. Equal dates mean *Unchanged*; otherwise the field values are compared.
- **Access control:**
  - Only signed-in administrators: of the instance, or of both the source and the target site. The *Target* list only shows sites the user administers.
  - Entries must belong to the source site and to a published site-scoped object; the publication item object itself cannot be published.
  - Items can only be removed from pending publications of the same site.
  - Unexpected errors return a generic message; the detail stays in the server log.
- **REST API** under `/o/site-object-publisher` (see below).

## Requirements

- Liferay DXP **2026.Q1** (this workspace targets `dxp-2026.q1.11-lts`).
- A user who is an administrator of the instance, or of both the source and the target site.
- The **Publication Item** object (company scope), where publications are stored. Pick the file that matches the instance default language (see step 2 of *How to test*):
  - `en_US`: [`modules/custom-site-object-publisher/objects/PublicationItem.json`](modules/custom-site-object-publisher/objects/PublicationItem.json)
  - `es_ES`: [`modules/custom-site-object-publisher/objects/PublicationItem_ES.json`](modules/custom-site-object-publisher/objects/PublicationItem_ES.json)
- At least one published **site-scoped** object to publish, for example one with a text field as title.
- Two sites: the source (B) and the target (A).

## How to test

1. **Deploy the module.**

   ```bash
   ./gradlew :modules:custom-site-object-publisher:deploy
   ```

2. **Pick the JSON for your instance language.** Liferay requires a label in the instance default language, so **the import fails** if the JSON does not have it:
   - Default language `en_US`: use `PublicationItem.json`.
   - Default language `es_ES`: use `PublicationItem_ES.json`.
   - Any other language: replace `en_US` in `PublicationItem.json` with it before importing, for example for `fr_FR`:

     ```bash
     sed -i 's/"en_US"/"fr_FR"/g' modules/custom-site-object-publisher/objects/PublicationItem.json
     ```

3. **Import the JSON** from step 2 and publish it if it is not published yet.
   - **UI:** *Control Panel > Objects > ⋮ > Import Object > **PublicationItem***.
4. **Create two sites:** *Site A* (target) and *Site B* (source).
5. **In Site B, add an entry** of your site-scoped object.
6. **Open the publisher:** in Site B, go to *Site Administration > Publishing > Site Object Publisher*.
7. **Select the target and object:** *Target* = Site A, *Object* = your object. The entry is shown as **New**.
8. **Mark it:** type `Test` in *Mark in*, then choose **Include in publication** in the entry menu.
9. **Publish:** select *Publication* = `Test` and click **Publish publication**.
10. **Check the result:**
    - Site A has the entry, with the same ERC.
    - In Site B the entry is **Unchanged**, and *Past Publications* lists `Test` as *Sent to* Site A.
    - In Site A, *Past Publications* lists `Test` as *Received from* Site B.
    - In Site B, ⋮ > *Permissions* of the entry: *Owner* only keeps *View* and *Update*.
11. **Try the other cases:**
    - Edit the entry in Site B: it shows as **Modified**; use **Publish now**.
    - Edit it in Site A: it shows as **Changed in target**; use **Restore from target**.
    - **Mark for deletion** and publish: it is removed from both sites.

To test relationships, add a second site-scoped object with a one-to-many relationship to the first one and publish the parent before the children.

## Configuration (optional)

To use another name for the Publication Item object, deploy `com.custom.site.object.publisher.internal.service.SitePublisher.config` into `osgi/configs`:

```
publicationItemObjectDefinitionName="C_MyPublicationItem"
```

## REST API

Base path `/o/site-object-publisher`. `groupId` is the source site and `targetGroupId` the target site. Every call requires a signed-in administrator (see *Access control*); with a browser session, Liferay also requires the `x-csrf-token` header.

| Method | Path | Use |
|---|---|---|
| GET | `/sites`, `/object-definitions` | Sites and site-scoped objects |
| GET | `/entries` | Work view (`page`, `pageSize`, `onlyChanged`, `search`) |
| POST | `/entries/{id}/publish`, `/entries/{id}/delete`, `/entries/{id}/restore` | Per-entry actions |
| POST, DELETE | `/items`, `/items/{itemId}` | Mark or unmark an entry |
| GET, DELETE | `/publications`, `/publication` | Active publications |
| POST | `/publication/publish` | Publish and archive |
| GET, DELETE | `/history`, `/history/items` | Past publications sent or received (`sourceGroupId` and `name` for items and delete) |

## Limitations

- **Only approved entries** are published. No workflow, drafts or scheduling.
- **Copied data:** the object's own fields and one-to-many relationships only. Attachments, categories, tags, permissions and friendly URLs are not copied: the target generates its own friendly URL from the title field on every publication.
- **Not transactional:** each entry is published separately, and each result is reported.
- **ERC required:** entries are matched only by ERC, so the same entry must have the same ERC in both sites.
- **Object actions** of the target run when its entries are created or updated.
- **Volume:** each page load compares all entries of the selected object (mostly a date check).
- **Owner permissions** are removed when an entry is published, not before. Roles that have *Delete* on the object can still delete entries directly.
- **No "modified by":** Liferay does not update the entry's user when an approved entry is edited, so the list only shows the modified date.
- **Liferay object permissions are not applied:** the module uses Liferay's local services, so access depends only on being an administrator of the sites involved, not on the permissions of each object or entry.
- **Site alignment:** pages, templates and fragments of both sites must be kept aligned by other means, for example LAR.

## Code

All under `modules/custom-site-object-publisher/src/main`:

| Piece | File |
|---|---|
| Logic | `java/.../internal/service/SitePublisher.java` |
| REST API | `java/.../internal/jaxrs/SiteObjectPublisherApplication.java` |
| Portlets and menu entries | `java/.../internal/portlet/*Portlet.java`, `java/.../internal/application/list/*PanelApp.java` |
| UI (vanilla JS + Clay, no build) | `resources/META-INF/resources/js/site-object-publisher.js`, `resources/META-INF/resources/css/site-object-publisher.css` |
