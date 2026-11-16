/**
 * Site Object Publisher: publishes object entries between sites
 * (source = current site).
 *
 * Build-free application (vanilla JS + Clay classes) on top of the REST API
 * /o/site-object-publisher of this module.
 */
(function () {
	const PAGE_SIZE = 50;

	const API = '/o/site-object-publisher';

	const STATUS_LABELS = {
		0: 'label-success',
		1: 'label-info',
		2: 'label-secondary',
		3: 'label-danger',
		7: 'label-info',
	};

	const TARGET_STATES = {
		changed: ['label-warning', 'Modified'],
		targetChanged: ['label-danger', 'Changed in target'],
		new: ['label-info', 'New'],
		same: ['label-secondary', 'Unchanged'],
	};

	const ITEM_STATES = {
		error: ['label-danger', 'Error'],
		pending: ['label-warning', 'Pending'],
		published: ['label-success', 'Published'],
	};

	const esc = (value) => {
		const div = document.createElement('div');

		div.textContent = value == null ? '' : String(value);

		return div.innerHTML;
	};

	const label = (css, text) =>
		'<span class="label ' + css + '"><span class="label-item label-item-expand">' +
		esc(text) + '</span></span>';

	const spritemap = () =>
		(window.Liferay && Liferay.Icons && Liferay.Icons.spritemap) ||
		Liferay.ThemeDisplay.getPathThemeImages() + '/clay/icons.svg';

	const icon = (name) =>
		'<svg class="lexicon-icon lexicon-icon-' + name +
		'" focusable="false" role="presentation"><use href="' + spritemap() +
		'#' + name + '"></use></svg>';

	const toast = (message, type) => {
		if (window.Liferay && Liferay.Util && Liferay.Util.openToast) {
			Liferay.Util.openToast({message, type: type || 'success'});
		}
	};

	const confirmAction = (message, onConfirm) => {
		if (window.Liferay && Liferay.Util && Liferay.Util.openConfirmModal) {
			Liferay.Util.openConfirmModal({
				message,
				onConfirm: (isConfirmed) => isConfirmed && onConfirm(),
			});
		}
		else if (window.confirm(message)) {
			onConfirm();
		}
	};

	const formatDate = (time) =>
		time ? new Date(Number(time)).toLocaleString() : '';

	function request(method, path, params) {
		const url = new URL(API + path, window.location.origin);

		Object.entries(params || {}).forEach(([key, value]) => {
			if (value !== undefined && value !== null && value !== '') {
				url.searchParams.set(key, value);
			}
		});

		return fetch(url, {
			headers: {
				'Accept': 'application/json',
				'x-csrf-token': Liferay.authToken,
			},
			method,
		}).then((response) =>
			response
				.json()
				.catch(() => ({}))
				.then((json) => {
					if (!response.ok) {
						throw new Error(json.error || 'Error ' + response.status);
					}

					return json;
				})
		);
	}

	function menu(items) {
		if (!items.length) {
			return '';
		}

		return (
			'<div class="dropdown sop-dropdown">' +
			'<button aria-expanded="false" aria-label="Actions" ' +
			'class="btn btn-monospaced btn-sm btn-unstyled component-action" ' +
			'data-action="toggle" type="button">' + icon('ellipsis-v') + '</button>' +
			'<div class="dropdown-menu dropdown-menu-right"><ul class="list-unstyled">' +
			items
				.map(([action, id, iconName, text]) =>
					action.startsWith('open:')
						? '<li><a class="dropdown-item sop-menu-item" href="' +
							esc(action.slice(5)) + '" rel="noopener noreferrer" target="_blank">' +
							icon(iconName) + '<span>' + esc(text) + '</span></a></li>'
						: '<li><button class="dropdown-item sop-menu-item" data-action="' +
							action + '" data-id="' + esc(id) + '" type="button">' +
							icon(iconName) + '<span>' + esc(text) + '</span></button></li>'
				)
				.join('') +
			'</ul></div></div>'
		);
	}

	const closeMenus = (root, except) =>
		root.querySelectorAll('.dropdown-menu.show').forEach((menuElement) => {
			if (menuElement !== except) {
				menuElement.classList.remove('show');
			}
		});

	/**
	 * Handles the action menus and delegates the other clicks to onAction.
	 */
	/**
	 * Places an open menu with fixed positioning next to its button, so it is
	 * never clipped by the table or the portlet container, and opens it
	 * upwards when there is no room below.
	 */
	function placeMenu(button, menuElement) {
		const rect = button.getBoundingClientRect();
		const margin = 8;

		menuElement.style.position = 'fixed';
		menuElement.style.left = 'auto';
		menuElement.style.right =
			Math.max(margin, window.innerWidth - rect.right) + 'px';
		menuElement.style.top = rect.bottom + 'px';
		menuElement.style.bottom = 'auto';
		menuElement.style.maxHeight = '';
		menuElement.style.overflowY = '';

		const height = menuElement.offsetHeight;
		const below = window.innerHeight - rect.bottom - margin;
		const above = rect.top - margin;

		if (height > below && above > below) {
			menuElement.style.top = 'auto';
			menuElement.style.bottom = window.innerHeight - rect.top + 'px';

			if (height > above) {
				menuElement.style.maxHeight = above + 'px';
				menuElement.style.overflowY = 'auto';
			}
		}
		else if (height > below) {
			menuElement.style.maxHeight = below + 'px';
			menuElement.style.overflowY = 'auto';
		}
	}

	function bindActions(root, onAction) {
		root.addEventListener('click', (event) => {
			const button = event.target.closest('[data-action]');

			if (!button) {
				closeMenus(root);

				return;
			}

			if (button.dataset.action === 'toggle') {
				const menuElement = button.nextElementSibling;

				closeMenus(root, menuElement);
				menuElement.classList.toggle('show');

				if (menuElement.classList.contains('show')) {
					placeMenu(button, menuElement);
				}
			}
			else {
				closeMenus(root);
				onAction(button.dataset.action, button.dataset.id, button);
			}
		});

		document.addEventListener('click', (event) => {
			if (!root.contains(event.target)) {
				closeMenus(root);
			}
		});

		// A fixed menu would stay in place while the page moves

		window.addEventListener(
			'scroll',
			(event) => {
				if (!(event.target instanceof Element) ||
					!event.target.closest('.dropdown-menu')) {

					closeMenus(root);
				}
			},
			true
		);
		window.addEventListener('resize', () => closeMenus(root));
	}

	/**
	 * "Past Publications" portlet: one row per executed "Publish publication"
	 * sent or received by this site (other site and date). Clicking a row
	 * expands its items.
	 */
	function mountHistory(root) {
		const groupId = root.dataset.groupId;

		const ITEM_RESULTS = {
			error: ['label-danger', 'Error'],
			published: ['label-success', 'Published'],
		};

		// The archived name is "name · date": the date has its own column

		const displayName = (name) => {
			const index = name.lastIndexOf(' · ');

			return index > 0 ? name.slice(0, index) : name;
		};

		root.innerHTML =
			'<div class="sop sop-history">' +
			'<table class="table table-autofit table-hover table-list sop-table">' +
			'<thead><tr><th class="table-cell-expand">Publication</th>' +
			'<th>Direction</th><th>Date</th><th>Items</th></tr></thead>' +
			'<tbody><tr><td colspan="4"><span class="loading-animation"></span></td></tr></tbody>' +
			'</table></div>';

		const tbody = root.querySelector('tbody');

		const itemsTable = (items) =>
			'<table class="table table-sm mb-0"><tbody>' +
			items
				.map(
					(item) =>
						'<tr><td class="table-cell-expand">' + esc(item.label) +
						' <small class="text-secondary">#' + esc(item.sourceEntryId) + ' · ' +
						esc(item.erc) + '</small></td>' +
						'<td>' + esc(item.objectDefinitionLabel || '') + '</td>' +
						'<td>' +
						(item.action === 'delete'
							? label('label-danger', 'Delete')
							: label('label-info', 'Publish')) +
						'</td><td>' +
						label.apply(null, ITEM_RESULTS[item.status] || ['label-secondary', item.status]) +
						(item.message
							? ' <small class="text-secondary">' + esc(item.message) + '</small>'
							: '') +
						'</td><td>' +
						(item.targetURL
							? '<a href="' + esc(item.targetURL) + '" rel="noopener" target="_blank" title="View in target">' +
								icon('globe') + '</a>'
							: '') +
						'</td></tr>'
				)
				.join('') +
			'</tbody></table>';

		const direction = (publication) =>
			publication.direction === 'sent'
				? label('label-info', 'Sent to') + ' ' + esc(publication.otherGroupName)
				: label('label-success', 'Received from') + ' ' + esc(publication.otherGroupName);

		request('GET', '/history', {groupId})
			.then((publications) => {
				tbody.innerHTML = publications.length
					? publications
							.map(
								(publication) =>
									'<tr class="sop-history-row" data-name="' + esc(publication.name) +
									'" data-source-group-id="' + esc(publication.sourceGroupId) + '">' +
									'<td class="table-cell-expand">' + icon('angle-right') + ' <strong>' +
									esc(displayName(publication.name)) + '</strong></td>' +
									'<td>' + direction(publication) + '</td>' +
									'<td>' + formatDate(publication.publishedDate) + '</td>' +
									'<td>' + (publication.published + publication.errors) +
									(publication.errors
										? ' ' + label('label-danger', publication.errors + ' with errors')
										: '') +
									'</td></tr>'
							)
							.join('')
					: '<tr><td class="text-center text-secondary" colspan="4">' +
						'No publication has been sent or received yet</td></tr>';
			})
			.catch((error) => {
				tbody.innerHTML =
					'<tr><td class="text-danger" colspan="4">' + esc(error.message) + '</td></tr>';
			});

		root.addEventListener('click', (event) => {
			if (event.target.closest('a')) {
				return;
			}

			const row = event.target.closest('.sop-history-row');

			if (!row) {
				return;
			}

			const next = row.nextElementSibling;

			if (next && next.classList.contains('sop-history-items')) {
				next.remove();
				row.classList.remove('open');

				return;
			}

			row.classList.add('open');

			const itemsRow = document.createElement('tr');

			itemsRow.className = 'sop-history-items';
			itemsRow.innerHTML =
				'<td colspan="4"><span class="loading-animation"></span></td>';

			row.after(itemsRow);

			request('GET', '/history/items', {
				groupId,
				name: row.dataset.name,
				sourceGroupId: row.dataset.sourceGroupId,
			})
				.then((items) => {
					itemsRow.firstChild.innerHTML = itemsTable(items);
				})
				.catch((error) => {
					itemsRow.firstChild.innerHTML =
						'<span class="text-danger">' + esc(error.message) + '</span>';
				});
		});
	}

	function mount(root) {
		if (root.dataset.sopMounted) {
			return;
		}

		root.dataset.sopMounted = 'true';

		if (root.dataset.mode === 'history') {
			mountHistory(root);

			return;
		}

		const groupId = root.dataset.groupId;

		const state = {
			definitions: [],
			entries: [],
			lastPage: 1,
			objectDefinitionId: '',
			onlyChanged: true,
			page: 1,
			publication: '',
			publications: [],
			search: '',
			selected: new Set(),
			sites: [],
			targetGroupId: '',
			totalCount: 0,
			workPublication: '',
		};

		root.innerHTML =
			'<div class="sop">' +
			'<div class="sop-filters">' +
			'<div class="form-group"><label>Publication</label>' +
			'<select class="form-control" data-filter="publication"></select></div>' +
			'<div class="form-group"><label>Target</label>' +
			'<select class="form-control" data-filter="target"></select></div>' +
			'<div class="form-group" data-work><label>Object</label>' +
			'<select class="form-control" data-filter="definition"></select></div>' +
			'<div class="form-group" data-work><label>Search</label>' +
			'<input class="form-control" data-filter="search" placeholder="Text in any field or ERC" type="search"></div>' +
			'<div class="form-group" data-work><label>Mark in</label>' +
			'<input class="form-control" data-filter="work" list="sop-publications" placeholder="Publication name" type="text">' +
			'<datalist id="sop-publications"></datalist></div>' +
			'<div class="form-group custom-control custom-checkbox" data-work>' +
			'<label><input checked class="custom-control-input" data-filter="onlyChanged" type="checkbox">' +
			'<span class="custom-control-label"><span class="custom-control-label-text">Only with changes</span></span></label></div>' +
			'<button class="btn btn-secondary btn-sm" data-action="refresh" data-target-only type="button">' +
			icon('reload') + ' Refresh</button>' +
			'<button class="btn btn-primary btn-sm d-none" data-action="publishPublication" type="button">' +
			icon('upload') + ' Publish publication</button>' +
			'<button class="btn btn-secondary btn-sm d-none" data-action="deletePublication" type="button">' +
			icon('trash') + ' Delete publication</button>' +
			'</div>' +
			'<div class="sop-info text-secondary"></div>' +
			'<div class="sop-bulk" data-work>' +
			'<span class="sop-bulk-count text-secondary">0 selected</span>' +
			'<button class="btn btn-secondary btn-sm" data-action="bulkInclude" disabled type="button">' +
			icon('plus') + ' Include selected</button>' +
			'<button class="btn btn-secondary btn-sm" data-action="bulkDelete" disabled type="button">' +
			icon('trash') + ' Mark selected for deletion</button>' +
			'</div>' +
			'<table class="table table-autofit table-hover table-list sop-table">' +
			'<thead></thead><tbody></tbody></table>' +
			'<div class="sop-pager"></div>' +
			'<div class="sop-missing"></div>' +
			'</div>';

		const field = (name) => root.querySelector('[data-filter="' + name + '"]');
		const thead = root.querySelector('thead');
		const tbody = root.querySelector('tbody');
		const info = root.querySelector('.sop-info');
		const missing = root.querySelector('.sop-missing');

		const options = (items, value, text, empty) =>
			(empty ? '<option value="">' + esc(empty) + '</option>' : '') +
			items
				.map(
					(item) =>
						'<option value="' + esc(value(item)) + '">' + esc(text(item)) +
						'</option>'
				)
				.join('');

		function renderFilters() {
			field('publication').innerHTML = options(
				state.publications,
				(item) => item.name,
				(item) => item.name + ' · ' + item.pending + ' pending',
				'— Work: all entries —'
			);
			field('publication').value = state.publication;

			field('target').innerHTML = options(
				state.sites,
				(item) => item.id,
				(item) => item.name + ' (' + item.friendlyURL + ')',
				'— Select target —'
			);
			field('target').value = state.targetGroupId;

			field('definition').innerHTML = options(
				state.definitions,
				(item) => item.id,
				(item) => item.label
			);
			field('definition').value = state.objectDefinitionId;

			root.querySelector('#sop-publications').innerHTML = options(
				state.publications,
				(item) => item.name,
				(item) => item.name
			);

			// Until a target is selected only Publication and Target are shown

			const hasTarget = Boolean(state.targetGroupId);
			const publicationMode = Boolean(state.publication);

			root
				.querySelectorAll('[data-work]')
				.forEach((element) =>
					element.classList.toggle('d-none', publicationMode || !hasTarget)
				);

			root
				.querySelectorAll('[data-target-only]')
				.forEach((element) => element.classList.toggle('d-none', !hasTarget));

			root
				.querySelectorAll('[data-action="publishPublication"], [data-action="deletePublication"]')
				.forEach((button) =>
					button.classList.toggle('d-none', !publicationMode || !hasTarget)
				);
		}

		const element = (entry) =>
			'<td class="table-cell-expand"><strong>' + esc(entry.label) +
			'</strong><br><small class="text-secondary">#' + esc(entry.id) + ' · ' +
			esc(entry.externalReferenceCode || entry.erc) + '</small></td>';

		const target = (entry) =>
			label.apply(null, TARGET_STATES[entry.targetState] || TARGET_STATES.new);

		const links = (entry) => {
			const items = [];

			if (entry.targetURL) {
				items.push(['open:' + entry.targetURL, entry.id, 'globe', 'View in target']);
			}

			if (entry.sourceURL) {
				items.push(['open:' + entry.sourceURL, entry.id, 'view', 'View in this site']);
			}

			if (entry.editURL) {
				items.push(['open:' + entry.editURL, entry.id, 'pencil', 'Edit']);
			}

			return items;
		};

		function renderWork() {
			thead.innerHTML =
				'<tr><th class="sop-check"><input aria-label="Select all" data-select="all" type="checkbox"></th>' +
				'<th class="table-cell-expand">Item</th><th>Target</th>' +
				'<th>Status</th><th>Modified</th>' +
				'<th>Publications</th><th></th></tr>';

			const entries = state.entries;

			info.textContent =
				state.totalCount + ' entry(ies)' +
				(state.lastPage > 1
					? ' · page ' + state.page + ' of ' + state.lastPage
					: '');

			renderPager();

			tbody.innerHTML = entries.length
				? entries
						.map(
							(entry) =>
								'<tr><td class="sop-check"><input aria-label="Select" data-select="' +
								esc(entry.id) + '"' + (state.selected.has(String(entry.id)) ? ' checked' : '') +
								' type="checkbox"></td>' + element(entry) +
								'<td>' + target(entry) + '</td>' +
								'<td>' +
								label(STATUS_LABELS[entry.status] || 'label-secondary', entry.statusLabel) +
								'</td>' +
								'<td>' + formatDate(entry.modifiedDate) + '</td>' +
								'<td>' +
								entry.items
									.map((item) =>
										label(
											item.action === 'delete' ? 'label-danger' : 'label-info',
											item.publication +
												(item.action === 'delete' ? ' · delete' : '')
										)
									)
									.join(' ') +
								'</td>' +
								'<td>' +
								menu([
									['include', entry.id, 'plus', 'Include in publication'],
									['markDelete', entry.id, 'trash', 'Mark for deletion'],
									['publishEntry', entry.id, 'upload', 'Publish now'],
									['deleteEntry', entry.id, 'times-circle', 'Delete now'],
									...(entry.targetState === 'changed' || entry.targetState === 'targetChanged'
										? [['restoreEntry', entry.id, 'restore', 'Restore from target']]
										: []),
									...links(entry),
								]) +
								'</td></tr>'
						)
						.join('')
				: '<tr><td class="text-center text-secondary" colspan="7">' +
					'No entries match the current filters</td></tr>';

			missing.innerHTML = '';

			renderSelection();
		}

		function visibleIds() {
			return Array.from(root.querySelectorAll('tbody [data-select]')).map(
				(checkbox) => checkbox.dataset.select
			);
		}

		function renderSelection() {
			const ids = visibleIds();
			const count = ids.filter((id) => state.selected.has(id)).length;
			const all = root.querySelector('[data-select="all"]');

			root.querySelector('.sop-bulk-count').textContent =
				count + ' selected';

			root
				.querySelectorAll('[data-action="bulkInclude"], [data-action="bulkDelete"]')
				.forEach((button) => (button.disabled = !count));

			if (all) {
				all.checked = ids.length > 0 && count === ids.length;
				all.indeterminate = count > 0 && count < ids.length;
			}
		}

		function renderPublication(publication) {
			thead.innerHTML =
				'<tr><th class="table-cell-expand">Item</th><th>Object</th>' +
				'<th>Action</th><th>Target</th><th>Status</th><th></th></tr>';

			const itemState = (item) =>
				label.apply(null, ITEM_STATES[item.status] || ITEM_STATES.pending) +
				(item.message
					? '<br><small class="text-secondary">' + esc(item.message) + '</small>'
					: '');

			const action = (item) =>
				item.action === 'delete'
					? label('label-danger', 'Delete')
					: label('label-info', 'Publish');

			info.textContent =
				publication.items.length + ' entry(ies) in "' + publication.publication + '"';

			tbody.innerHTML = publication.items.length
				? publication.items
						.map(
							(entry) =>
								'<tr>' + element(entry) +
								'<td>' + esc(entry.objectDefinitionLabel) + '</td>' +
								'<td>' + action(entry.item) + '</td>' +
								'<td>' + target(entry) + '</td>' +
								'<td>' + itemState(entry.item) + '</td>' +
								'<td>' +
								menu([
									['removeItem', entry.item.id, 'times', 'Remove from publication'],
									...links(entry),
								]) +
								'</td></tr>'
						)
						.join('')
				: '<tr><td class="text-center text-secondary" colspan="6">' +
					'No entries</td></tr>';

			missing.innerHTML = publication.missing.length
				? '<div class="alert alert-info"><strong>' + publication.missing.length +
					' entry(ies) recorded in the publication that no longer exist in the source:</strong>' +
					'<ul class="mb-0">' +
					publication.missing
						.map(
							(item) =>
								'<li>' + esc(item.label) + ' <small class="text-secondary">(' +
								esc(item.erc) + ')</small> · ' + action(item) + ' · ' +
								itemState(item) +
								' <button class="btn btn-link btn-sm" data-action="removeItem" data-id="' +
								esc(item.id) + '" type="button">Remove</button></li>'
						)
						.join('') +
					'</ul></div>'
				: '';
		}

		function renderPager() {
			const pager = root.querySelector('.sop-pager');

			if (state.publication || state.lastPage <= 1) {
				pager.innerHTML = '';

				return;
			}

			pager.innerHTML =
				'<button class="btn btn-secondary btn-sm" data-action="pagePrev"' +
				(state.page <= 1 ? ' disabled' : '') + ' type="button">' +
				icon('angle-left') + ' Previous</button>' +
				'<span class="text-secondary">Page ' + state.page + ' of ' +
				state.lastPage + '</span>' +
				'<button class="btn btn-secondary btn-sm" data-action="pageNext"' +
				(state.page >= state.lastPage ? ' disabled' : '') + ' type="button">' +
				'Next ' + icon('angle-right') + '</button>';
		}

		function load() {
			renderFilters();

			state.selected.clear();

			// Nothing is listed until a target is selected

			if (!state.targetGroupId) {
				info.textContent = 'Select a target site to compare its entries.';
				thead.innerHTML = '';
				tbody.innerHTML = '';
				missing.innerHTML = '';
				root.querySelector('.sop-pager').innerHTML = '';

				return Promise.resolve();
			}

			if (state.publication) {
				root.querySelector('.sop-pager').innerHTML = '';
			}

			tbody.innerHTML =
				'<tr><td colspan="7"><span class="loading-animation"></span></td></tr>';

			const loading = state.publication
				? request('GET', '/publication', {
						groupId,
						name: state.publication,
						targetGroupId: state.targetGroupId,
					}).then(renderPublication)
				: request('GET', '/entries', {
						groupId,
						objectDefinitionId: state.objectDefinitionId,
						onlyChanged: state.onlyChanged,
						page: state.page,
						search: state.search,
						pageSize: PAGE_SIZE,
						targetGroupId: state.targetGroupId,
					}).then((json) => {
						state.entries = json.items || [];
						state.lastPage = json.lastPage || 1;
						state.page = json.page || 1;
						state.totalCount = json.totalCount || 0;

						renderWork();
					});

			return loading.catch((error) => {
				tbody.innerHTML =
					'<tr><td class="text-danger" colspan="7">' + esc(error.message) +
					'</td></tr>';
			});
		}

		function reloadPublications() {
			return request('GET', '/publications', {groupId}).then(
				(publications) => {
					state.publications = publications;
				}
			);
		}

		const done = (json) => {
			toast(json.message || 'Done', json.result === 'partial' ? 'warning' : 'success');

			return reloadPublications().then(load);
		};

		const fail = (error) => toast(error.message, 'danger');

		function workPublication() {
			state.workPublication = field('work').value.trim();

			if (!state.workPublication) {
				toast('Enter an existing publication or a new name in "Mark in"', 'warning');
				field('work').focus();
			}

			return state.workPublication;
		}

		function addItems(ids, itemAction) {
			const name = workPublication();

			if (!name || !ids.length) {
				return;
			}

			const results = {errors: [], ok: 0};

			ids
				.reduce(
					(chain, id) =>
						chain.then(() =>
							request('POST', '/items', {
								action: itemAction,
								groupId,
								name,
								objectEntryId: id,
							})
								.then(() => results.ok++)
								.catch((error) => results.errors.push('#' + id + ': ' + error.message))
						),
					Promise.resolve()
				)
				.then(() => {
					const verb = itemAction === 'delete' ? 'marked for deletion' : 'included';

					done({
						message:
							results.ok + ' entry(ies) ' + verb + ' in "' + name + '"' +
							(results.errors.length ? '. Errors: ' + results.errors.join('; ') : ''),
						result: results.errors.length ? 'partial' : 'ok',
					});
				});
		}

		function run(action, id) {
			if (action.startsWith('open:')) {
				window.open(action.slice(5), '_blank', 'noopener');
			}
			else if (action === 'include' || action === 'markDelete') {
				addItems([id], action === 'markDelete' ? 'delete' : 'publish');
			}
			else if (action === 'bulkInclude' || action === 'bulkDelete') {
				addItems(
					visibleIds().filter((selectedId) => state.selected.has(selectedId)),
					action === 'bulkDelete' ? 'delete' : 'publish'
				);
			}
			else if (action === 'removeItem') {
				request('DELETE', '/items/' + id, {groupId}).then(done).catch(fail);
			}
			else if (action === 'publishEntry') {
				confirmAction('Publish this entry to the target site?', () =>
					request('POST', '/entries/' + id + '/publish', {
						groupId,
						targetGroupId: state.targetGroupId,
					})
						.then(done)
						.catch(fail)
				);
			}
			else if (action === 'deleteEntry') {
				confirmAction(
					'Delete this entry now in the target site and in this site? This cannot be undone.',
					() =>
						request('POST', '/entries/' + id + '/delete', {
							groupId,
							targetGroupId: state.targetGroupId,
						})
							.then(done)
							.catch(fail)
				);
			}
			else if (action === 'restoreEntry') {
				confirmAction(
					'Restore this entry with the values of the target site? Its changes are lost and its pending marks are removed.',
					() =>
						request('POST', '/entries/' + id + '/restore', {
							groupId,
							targetGroupId: state.targetGroupId,
						})
							.then(done)
							.catch(fail)
				);
			}
			else if (action === 'deletePublication') {
				confirmAction(
					'Delete the publication "' + state.publication + '"? Its entries are unassigned; no entry is changed.',
					() =>
						request('DELETE', '/publication', {
							groupId,
							name: state.publication,
						})
							.then((json) => {
								state.publication = '';

								return done(json);
							})
							.catch(fail)
				);
			}
			else if (action === 'publishPublication') {
				confirmAction(
					'Publish "' + state.publication + '" to the target site?',
					() =>
						request('POST', '/publication/publish', {
							groupId,
							name: state.publication,
							targetGroupId: state.targetGroupId,
						})
							.then((json) => {

								// It is archived: see "Past Publications"

								state.publication = '';

								return done(json);
							})
							.catch(fail)
				);
			}
		}

		bindActions(root, (action, id) => {
			if (action === 'pagePrev' || action === 'pageNext') {
				state.page += action === 'pageNext' ? 1 : -1;

				load();
			}
			else if (action === 'refresh') {
				reloadPublications().then(load);
			}
			else {
				run(action, id);
			}
		});

		root.addEventListener('change', (event) => {
			const select = event.target.dataset.select;

			if (select === 'all') {
				visibleIds().forEach((id) =>
					event.target.checked ? state.selected.add(id) : state.selected.delete(id)
				);
				root
					.querySelectorAll('tbody [data-select]')
					.forEach((checkbox) => (checkbox.checked = event.target.checked));

				renderSelection();

				return;
			}
			else if (select) {
				event.target.checked
					? state.selected.add(select)
					: state.selected.delete(select);

				renderSelection();

				return;
			}

			const filter = event.target.dataset.filter;

			if (filter === 'publication') {
				state.publication = event.target.value;
			}
			else if (filter === 'target') {
				state.targetGroupId = event.target.value;
			}
			else if (filter === 'definition') {
				state.objectDefinitionId = event.target.value;
			}
			else if (filter === 'onlyChanged') {
				state.onlyChanged = event.target.checked;
			}
			else if (filter === 'search') {
				state.search = event.target.value.trim();
			}
			else if (filter === 'work') {
				state.workPublication = event.target.value.trim();

				return;
			}
			else {
				return;
			}

			state.page = 1;

			load();
		});

		Promise.all([
			request('GET', '/object-definitions', {groupId}),
			request('GET', '/sites', {groupId}),
			reloadPublications(),
		])
			.then(([definitions, sites]) => {
				state.definitions = definitions;
				state.objectDefinitionId = definitions.length
					? String(definitions[0].id)
					: '';
				state.sites = sites;

				return load();
			})
			.catch((error) => {
				root.innerHTML =
					'<div class="alert alert-danger">' + esc(error.message) + '</div>';
			});
	}

	function mountAll() {
		document.querySelectorAll('.site-object-publisher').forEach(mount);
	}

	mountAll();

	if (window.Liferay && Liferay.on) {
		Liferay.on('endNavigate', mountAll);
		Liferay.on('allPortletsReady', mountAll);
	}
})();
