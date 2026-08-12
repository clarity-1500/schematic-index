package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import com.fudgedy.schematicindex.catalogue.Backend;
import com.fudgedy.schematicindex.catalogue.Bookmarks;
import com.fudgedy.schematicindex.catalogue.Catalogue;
import com.fudgedy.schematicindex.catalogue.Category;
import com.fudgedy.schematicindex.catalogue.Download;
import com.fudgedy.schematicindex.catalogue.Follows;
import com.fudgedy.schematicindex.catalogue.NewsFeed;
import com.fudgedy.schematicindex.catalogue.SchematicEntry;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The catalogue browser. Layout only - entries come from {@link MockCatalogue} and images from
 * {@link ImageStore}, so the whole screen works with no backend.
 */
public class IndexScreen extends Screen {
	private static final int OUTER_MARGIN = 8;
	private static final int CONTENT_MAX_WIDTH = 720;
	private static final int TOP_BAR_HEIGHT = 32;
	private static final int RAIL_WIDTH = 34;
	private static final int RAIL_ITEM_HEIGHT = 34;
	/** How far a rail icon grows on hover - larger than a pill's, so the lift is easy to feel. */
	private static final float RAIL_HOVER_GROW = 0.14F;
	private static final int RAIL_ITEM_GAP = 8;
	private static final int GUTTER = 6;
	private static final int CAPTION_HEIGHT = 28;
	private static final int CHIP_HEIGHT = 14;
	private static final int CHIP_GAP = 4;
	private static final int SCROLL_STEP = 24;
	private static final int HEART_SIZE = 9;
	private static final int FIELD_HEIGHT = 16;
	/** How many cards a page of the grid reveals; more load as the scroll nears the bottom. */
	private static final int PAGE_SIZE = 12;
	/** Room left below the last row for the "loading more" indicator while a page is pending. */
	private static final int LOADING_ROW = 20;

	/** When each post was last liked, so the heart can play its pop. Purely visual, so not persisted. */
	private static final Map<String, Long> LIKE_POPS = new HashMap<>();

	/** Last-seen download state per post, so the finish/fail sound plays once on the transition. */
	private final Map<String, Download.State> downloadStates = new HashMap<>();

	private enum Page {
		BROWSE("Browse"),
		SAVED("Saved"),
		NEWS("News"),
		UPLOAD("Upload"),
		SETTINGS("Settings");

		private final String label;

		Page(String label) {
			this.label = label;
		}

		ItemStack icon() {
			return switch (this) {
				case BROWSE -> new ItemStack(Items.SPYGLASS);
				case SAVED -> new ItemStack(Items.ENDER_CHEST);
				case NEWS -> new ItemStack(Items.WRITTEN_BOOK);
				case UPLOAD -> new ItemStack(Items.WRITABLE_BOOK);
				case SETTINGS -> new ItemStack(Items.ANVIL);
			};
		}
	}

	private final @Nullable Screen parent;
	private final List<SchematicEntry> visible = new ArrayList<>();

	private Page page = Page.BROWSE;
	private Category category = Category.ALL;
	private Catalogue.Sort sort = Catalogue.Sort.NEWEST;
	private String query = "";

	private int contentX;
	private int contentWidth;
	private int chipRowHeight = 26;
	private int gridTop;
	private int gridBottom;
	private int columns = 4;
	private int cardWidth = 120;
	private int cardHeight = 96;

	private float scroll;
	private float maxScroll;
	/** How many of {@link #visible} are currently revealed - grows a page at a time (infinite scroll). */
	private int shownCount = PAGE_SIZE;

	private EditBox searchBox;
	private EditBox codeBox;
	private EditBox titleBox;
	private EditBox thumbnailBox;
	private EditBox designerBox;
	private EditBox descriptionBox;

	private final Rect closeButton = new Rect();
	private final Rect sortButton = new Rect();
	private final Rect retryButton = new Rect();
	private final List<Rect> chipRects = new ArrayList<>();
	private final List<Category> chipOrder = new ArrayList<>();
	private final List<Rect> railRects = new ArrayList<>();

	// Upload form state
	private final Rect unlockButton = new Rect();
	private final Rect signOutButton = new Rect();
	private final Rect formCategoryButton = new Rect();
	private final Rect formImagePrev = new Rect();
	private final Rect formImageNext = new Rect();
	private final Rect uploadPicturesButton = new Rect();
	private final Rect uploadSchematicButton = new Rect();
	private @Nullable Path formSchematic;
	private final List<Path> formPictures = new ArrayList<>();
	private int formPictureStart = -1;
	private int formPicturePreview;
	private final Rect postButton = new Rect();
	private Category formCategory = Category.FARMS;
	private String formStatus = "";

	private @Nullable SchematicEntry detail;
	private long detailOpenedAt;
	private int detailImage;
	private boolean detailModel;
	private float detailYaw = 35.0F;
	private float detailPitch = 28.0F;
	private float detailZoom = 1.0F;
	private boolean orbiting;
	private boolean cutaway = true;
	private boolean freeLook;
	private double @Nullable [] freeEye;
	private final Rect cutawayToggle = new Rect();
	private final Rect freeLookToggle = new Rect();
	private final Rect resetViewButton = new Rect();
	private final Rect layerSlider = new Rect();
	private final Rect detailImageRect = new Rect();
	private final Rect detailPrev = new Rect();
	private final Rect detailNext = new Rect();
	private final Rect detailDownload = new Rect();
	private final Rect detailPreview3d = new Rect();
	private final Rect detailSave = new Rect();
	private final Rect detailFollow = new Rect();
	private final Rect detailClose = new Rect();
	private final Rect detailCornerClose = new Rect();
	private final Rect detailHeart = new Rect();
	/** Set when a download would replace an existing file and the overwrite prompt is on. */
	private @Nullable SchematicEntry pendingOverwrite;
	private final Rect overwriteReplace = new Rect();
	private final Rect overwriteCancel = new Rect();
	/** The report affordance and, when open, the reason picker over the detail modal. */
	private static final String[] REPORT_REASONS = {
			"NSFW / Explicit Content", "Stealing Credit", "Spam / Misleading", "Other"};
	/** Server reason codes, aligned to {@link #REPORT_REASONS} by index. */
	private static final String[] REPORT_CODES = {"NSFW", "STOLEN", "SPAM", "OTHER"};
	private final Rect detailReport = new Rect();
	private boolean reportOpen;
	private final Rect[] reportReasonRects = new Rect[REPORT_REASONS.length];
	private final Rect reportCancel = new Rect();
	/** Second report step: a context box to explain the report. */
	private boolean reportContextOpen;
	private int reportReasonIndex = -1;
	private String reportContext = "";
	private final Rect reportSubmit = new Rect();
	private static final int REPORT_CONTEXT_MAX = 99;
	/** 1 = every layer shown; lower values hide the top of the build so interiors can be read. */
	private float detailLayer = 1.0F;
	private boolean draggingLayer;
	/** While following, the Follow button first asks to confirm before it actually unfollows. */
	private boolean followConfirm;
	private long followConfirmAt;
	private String status = "";

	// Settings page controls
	private final Rect soundsToggle = new Rect();
	private final Rect overwriteToggle = new Rect();
	private final Rect changeFolderButton = new Rect();
	private final Rect openFolderButton = new Rect();
	private final Rect resetFolderButton = new Rect();
	private final Rect gridDensityButton = new Rect();
	private final Rect clearCacheButton = new Rect();
	private final Rect toastsToggle = new Rect();
	private final Rect notificationsToggle = new Rect();
	private final Rect termsButton = new Rect();

	// First-run tutorial: a short spotlight tour of the layout.
	private static final String[][] TUTORIAL = {
			{"Welcome to The Schematic Index",
					"Browse the latest community schematics via posts and download them directly into your "
							+ "schematics folder."},
			{"Find your way around",
					"Use the left bar to switch between Browse, Saved, News, Upload and Settings."},
			{"Filter by category",
					"Filter posts by seeing exactly what kind of schematic you want with our tags."},
			{"Search",
					"Use the search bar to search for schematic names, the posters, and even the designers."},
			{"Explore posts",
					"Click on a post to view more images of the build or even preview it in 3D. You can "
							+ "download the schematic or save the post for later."}};
	private boolean tutorialActive;
	private boolean tutorialShown;
	private int tutorialStep;
	private final Rect tutorialNext = new Rect();
	private final Rect tutorialBack = new Rect();
	private final Rect tutorialSkip = new Rect();

	// Terms of service, shown before anything else on first open and gating the online features.
	private static final String TERMS_BODY =
			"By using The Schematic Index, you agree to these terms. If you do not agree, you cannot use "
					+ "the online service.\n\n"
					+ "Data & Privacy: The Service connects to external servers to sync the online catalog. "
					+ "It transmits a random local identifier along with your interactions (likes, downloads, "
					+ "and reports). You can revoke consent anytime in Settings, which disables online "
					+ "connectivity.\n\n"
					+ "Content Ownership: Do not upload content you do not have the legal rights to "
					+ "distribute. By uploading, you grant us a license to host and share your schematic. We "
					+ "reserve the right to remove infringing content and terminate access for abuse.\n\n"
					+ "Disclaimer: This service is provided \"as-is.\" We are not liable for server downtime "
					+ "or issues caused by third-party files.";
	private boolean tosOpen;
	private float tosScroll;
	private float tosMaxScroll;
	/** Set once the reader reaches the bottom; the Agree/Decline buttons stay disabled until then. */
	private boolean tosScrolledBottom;
	private final Rect tosAgree = new Rect();
	private final Rect tosDecline = new Rect();

	public IndexScreen(@Nullable Screen parent) {
		super(Component.literal("The Schematic Index"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ImageStore.discover();
		SchematicPreview.discover();
		Catalogue.ensureLoaded();

		int available = this.width - RAIL_WIDTH - OUTER_MARGIN * 2;
		this.contentWidth = Math.min(available, CONTENT_MAX_WIDTH);
		this.contentX = RAIL_WIDTH + OUTER_MARGIN + (available - this.contentWidth) / 2;

		// Grid density shifts the width-based column count: more columns = smaller thumbnails.
		this.columns = Math.max(2, Math.min(7, columnsFor(this.contentWidth) + Settings.gridDensity()));
		this.cardWidth = (this.contentWidth - GUTTER * (this.columns - 1)) / this.columns;
		this.cardHeight = imageHeight(this.cardWidth) + CAPTION_HEIGHT;

		int searchWidth = this.searchWidth();
		int searchX = this.contentX + (this.contentWidth - searchWidth) / 2;
		int searchY = (TOP_BAR_HEIGHT - 16) / 2;

		this.searchBox = this.textField(searchX + 6, searchY + 4, searchWidth - 12, "Search", this.query);
		this.searchBox.setResponder(value -> {
			this.query = value;
			this.refilter();
		});

		this.closeButton.set(this.contentX + this.contentWidth - 38, searchY, 38, 16);

		this.railRects.clear();

		// Space the tabs out evenly and centre the group vertically, so they read as a deliberate set
		// rather than a stack crammed under the top bar.
		int railCount = Page.values().length;
		int railBlock = RAIL_ITEM_HEIGHT + RAIL_ITEM_GAP;
		int groupHeight = railCount * RAIL_ITEM_HEIGHT + (railCount - 1) * RAIL_ITEM_GAP;
		int railTop = Math.max(TOP_BAR_HEIGHT + 10,
				TOP_BAR_HEIGHT + (this.height - TOP_BAR_HEIGHT - groupHeight) / 2);

		for (int i = 0; i < railCount; i++) {
			Rect rect = new Rect();
			rect.set(0, railTop + i * railBlock, RAIL_WIDTH, RAIL_ITEM_HEIGHT);
			this.railRects.add(rect);
		}

		this.buildUploadFields();

		// Cap the two names to what their destination can show, so neither can break a border. The
		// thumbnail is capped to a card's text width, the full name to the detail panel's title room.
		int avgBoldChar = Math.max(4, this.font.width(Theme.bold("abcdefghijklmnopqrstuvwxyz")) / 26 + 1);
		this.thumbnailBox.setMaxLength(Math.max(10, (this.cardWidth - 12) / avgBoldChar));
		this.titleBox.setMaxLength(Math.max(24, 190 / avgBoldChar));

		this.layoutChips();

		// The terms come first: until they are accepted, nothing else is reachable. The tour follows
		// once they agree. Both are guarded so a window resize does not reopen them.
		if (!Settings.termsAccepted()) {
			this.openTerms();
		} else {
			this.maybeStartTutorial();
		}

		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.gridBottom = this.height - OUTER_MARGIN;
		this.refilter();
	}

	private void maybeStartTutorial() {
		if (!Settings.tutorialSeen() && !this.tutorialShown) {
			this.tutorialShown = true;
			this.tutorialActive = true;
			this.tutorialStep = 0;
			this.page = Page.BROWSE;
		}
	}

	private EditBox textField(int x, int y, int width, String hint, String value) {
		EditBox box = new EditBox(this.font, x, y, width, 10, Component.literal(hint));
		box.setBordered(false);
		box.setMaxLength(120);
		box.setTextColor(Theme.TEXT);
		box.setHint(Component.literal(hint));
		box.setValue(value);
		this.addWidget(box);
		return box;
	}

	private void buildUploadFields() {
		int formWidth = Math.min(this.contentWidth, 300);
		int formX = this.contentX + (this.contentWidth - formWidth) / 2;
		int y = TOP_BAR_HEIGHT + 60;

		this.codeBox = this.textField(formX + 6, y + 4, formWidth - 12, "Access code",
				this.codeBox == null ? "" : this.codeBox.getValue());
		this.titleBox = this.textField(formX + 6, y + 4, formWidth - 12, "Schematic name",
				this.titleBox == null ? "" : this.titleBox.getValue());
		this.thumbnailBox = this.textField(formX + 6, y + 4, formWidth - 12, "Thumbnail name",
				this.thumbnailBox == null ? "" : this.thumbnailBox.getValue());
		this.designerBox = this.textField(formX + 6, y + 4, formWidth - 12, "Designed by",
				this.designerBox == null ? "" : this.designerBox.getValue());
		this.descriptionBox = this.textField(formX + 6, y + 4, formWidth - 12, "Description",
				this.descriptionBox == null ? "" : this.descriptionBox.getValue());
	}

	private int searchWidth() {
		return Math.max(80, Math.min(200, this.contentWidth - 260));
	}

	private static int columnsFor(int width) {
		if (width < 340) {
			return 2;
		}

		if (width < 500) {
			return 3;
		}

		if (width < 660) {
			return 4;
		}

		return 5;
	}

	private static int imageHeight(int cardWidth) {
		return Math.round(cardWidth * 9.0F / 16.0F);
	}

	private void layoutChips() {
		this.chipRects.clear();
		this.chipOrder.clear();

		// Only the two grid pages carry the category chip row.
		if (this.page != Page.BROWSE && this.page != Page.SAVED) {
			this.chipRowHeight = 26;
			return;
		}

		String sortLabel = "Sort: " + this.sort.label();
		int sortWidth = this.font.width(Theme.bold(sortLabel)) + 12;
		int firstRowLimit = this.contentX + this.contentWidth - sortWidth - 8;
		int limit = this.contentX + this.contentWidth;

		int x = this.contentX;
		int row = 0;

		for (Category value : Category.values()) {
			int width = this.font.width(Theme.bold(value.label())) + 12;
			int rowLimit = row == 0 ? firstRowLimit : limit;

			if (x + width > rowLimit && x > this.contentX) {
				row++;
				x = this.contentX;
			}

			Rect rect = new Rect();
			rect.set(x, TOP_BAR_HEIGHT + 6 + row * (CHIP_HEIGHT + CHIP_GAP), width, CHIP_HEIGHT);
			this.chipRects.add(rect);
			this.chipOrder.add(value);
			x += width + CHIP_GAP;
		}

		this.chipRowHeight = 6 + (row + 1) * CHIP_HEIGHT + row * CHIP_GAP + 6;
		this.sortButton.set(this.contentX + this.contentWidth - sortWidth, TOP_BAR_HEIGHT + 6, sortWidth, CHIP_HEIGHT);
	}

	private void refilter() {
		this.visible.clear();
		String needle = this.query.trim().toLowerCase(Locale.ROOT);

		for (SchematicEntry entry : Catalogue.posts()) {
			if (this.page == Page.SAVED && !isSaved(entry)) {
				continue;
			}

			if (this.category != Category.ALL && entry.category() != this.category) {
				continue;
			}

			if (!needle.isEmpty()
					&& !entry.title().toLowerCase(Locale.ROOT).contains(needle)
					&& !entry.poster().toLowerCase(Locale.ROOT).contains(needle)
					&& !entry.designer().toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}

			this.visible.add(entry);
		}

		Comparator<SchematicEntry> comparator = switch (this.sort) {
			// Genuinely newest first. This used to sort on the length of a "3d ago" string, which put
			// anything posted "just now" last.
			case NEWEST -> Comparator.comparingLong(SchematicEntry::postedAt).reversed();
			case DOWNLOADS -> Comparator.comparingInt(SchematicEntry::downloads).reversed();
			case LIKES -> Comparator.comparingInt(IndexScreen::likesOf).reversed();
		};
		this.visible.sort(comparator);

		// A filter/sort change starts the list back at the first page.
		this.shownCount = PAGE_SIZE;
		this.recomputeScrollBounds();
	}

	/** How many cards are actually on screen right now - the revealed window, capped by what matches. */
	private int shownCap() {
		return Math.min(this.shownCount, this.visible.size());
	}

	/** Recomputes {@link #maxScroll} from the revealed window, leaving room for the loading indicator. */
	private void recomputeScrollBounds() {
		int shown = this.shownCap();
		int rows = (shown + this.columns - 1) / this.columns;
		int contentHeight = rows * this.cardHeight + Math.max(0, rows - 1) * GUTTER;

		if (shown < this.visible.size()) {
			contentHeight += LOADING_ROW;
		}

		this.maxScroll = Math.max(0.0F, contentHeight - (this.gridBottom - this.gridTop));
		this.scroll = Math.min(this.scroll, this.maxScroll);
	}

	/** Reveals the next page once the scroll nears the bottom, until the whole list is shown. */
	private void maybeLoadMore() {
		if (this.shownCount >= this.visible.size()) {
			return;
		}

		if (this.scroll >= this.maxScroll - (this.cardHeight + GUTTER)) {
			this.shownCount = Math.min(this.visible.size(), this.shownCount + PAGE_SIZE);
			this.recomputeScrollBounds();
		}
	}

	private static boolean isSaved(SchematicEntry entry) {
		return Bookmarks.isSaved(entry.id());
	}

	private static void toggleSaved(SchematicEntry entry) {
		Theme.click(Bookmarks.toggleSaved(entry.id()) ? 1.3F : 0.9F);
	}

	private static int likesOf(SchematicEntry entry) {
		return entry.likes() + (isLikedBy(entry) ? 1 : 0);
	}

	/** Whether this device likes a post: the server's flag when present, else the local record. */
	private static boolean isLikedBy(SchematicEntry entry) {
		return entry.liked() || Bookmarks.isLiked(entry.id());
	}

	private static int imageSlot(SchematicEntry entry, int offset) {
		return entry.imageStart() + offset;
	}

	/** One gallery image of a post - a catalogue URL when the post has them, else the local test image. */
	private @Nullable Identifier imageTexture(SchematicEntry entry, int index) {
		List<String> urls = entry.imageUrls();

		if (!urls.isEmpty()) {
			return ImageStore.texture(urls.get(Math.floorMod(index, urls.size())));
		}

		return ImageStore.texture(imageSlot(entry, index));
	}

	/** The card image: the post's thumbnail URL when it has one, else its first gallery image. */
	private @Nullable Identifier thumbnailTexture(SchematicEntry entry) {
		if (entry.thumbnailUrl() != null && !entry.thumbnailUrl().isBlank()) {
			return ImageStore.texture(entry.thumbnailUrl());
		}

		return this.imageTexture(entry, 0);
	}

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ctx.fill(0, 0, this.width, this.height, Theme.BACKDROP);
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ImageStore.uploadPending();
		SchematicPreview.uploadPending();
		this.renderBackground(ctx, mouseX, mouseY, partialTick);

		boolean modalOpen = this.detail != null;
		int hoverX = modalOpen ? -1 : mouseX;
		int hoverY = modalOpen ? -1 : mouseY;

		if (this.page == Page.UPLOAD) {
			this.renderUpload(ctx, hoverX, hoverY, partialTick);
		} else if (this.page == Page.SETTINGS) {
			this.renderSettings(ctx, hoverX, hoverY);
		} else if (this.page == Page.NEWS) {
			this.renderNews(ctx);
		} else {
			this.renderGrid(ctx, hoverX, hoverY);
			this.renderScrollbar(ctx);
			this.renderChipRow(ctx, hoverX, hoverY);
		}

		this.renderRail(ctx, hoverX, hoverY);
		this.renderTopBar(ctx, hoverX, hoverY);

		// The search bar belongs to the grid pages only.
		this.searchBox.setVisible(this.gridPage());

		if (this.gridPage()) {
			this.searchBox.render(ctx, mouseX, mouseY, partialTick);
		} else if (this.searchBox.isFocused()) {
			this.searchBox.setFocused(false);
		}

		if (modalOpen) {
			this.renderDetail(ctx, mouseX, mouseY);

			if (this.pendingOverwrite != null) {
				this.renderOverwriteConfirm(ctx, mouseX, mouseY);
			} else if (this.reportOpen) {
				this.renderReportPicker(ctx, mouseX, mouseY);
			} else if (this.reportContextOpen) {
				this.renderReportContext(ctx, mouseX, mouseY);
			}
		}

		// Toasts sit above everything, including the modal.
		Toasts.render(ctx);

		// The first-run tour sits above even the toasts - it owns the screen until dismissed.
		if (this.tutorialActive) {
			this.renderTutorial(ctx, mouseX, mouseY);
		}

		// The terms gate sits above everything, including the tour.
		if (this.tosOpen) {
			this.renderTos(ctx, mouseX, mouseY);
		}
	}

	/** Opens the terms fresh: the reader starts at the top and must scroll down before answering. */
	private void openTerms() {
		this.tosOpen = true;
		this.tosScroll = 0.0F;
		this.tosScrolledBottom = false;
	}

	private void renderTos(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, 0xE0000000);

		int pad = 14;
		int line = this.font.lineHeight;
		int cardWidth = Math.min(this.width - 40, 360);
		int cardHeight = Math.min(this.height - 40, 210);
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.ACCENT);
		Theme.text(ctx, this.font, Theme.bold("Terms of Service"), x + pad, y + pad, Theme.TEXT);

		// The scrolling body sits between the title and the button row.
		int bodyTop = y + pad + line + 6;
		int bodyBottom = y + cardHeight - pad - 16 - 8;
		int bodyWidth = cardWidth - pad * 2;

		List<String> body = this.wrapParagraphs(TERMS_BODY, bodyWidth - 6);
		int contentHeight = body.size() * (line + 1);
		int viewport = bodyBottom - bodyTop;
		this.tosMaxScroll = Math.max(0.0F, contentHeight - viewport);
		this.tosScroll = Math.max(0.0F, Math.min(this.tosMaxScroll, this.tosScroll));

		// Once the reader has reached the end, unlock the buttons (and stay unlocked).
		if (this.tosScroll >= this.tosMaxScroll - 0.5F) {
			this.tosScrolledBottom = true;
		}

		ctx.enableScissor(x + pad, bodyTop, x + cardWidth - pad, bodyBottom);
		int ty = bodyTop - Math.round(this.tosScroll);

		for (String row : body) {
			Theme.text(ctx, this.font, row, x + pad, ty, Theme.TEXT_MUTE);
			ty += line + 1;
		}

		ctx.disableScissor();

		// Scroll track + thumb on the right, when there is more than fits.
		if (this.tosMaxScroll > 0.0F) {
			int trackX = x + cardWidth - pad + 2;
			Theme.roundedRect(ctx, trackX, bodyTop, 2, viewport, 1, Theme.SURFACE_CARD);
			int thumbHeight = Math.max(12, Math.round((float) viewport * viewport / contentHeight));
			int thumbY = bodyTop + Math.round((viewport - thumbHeight) * (this.tosScroll / this.tosMaxScroll));
			Theme.roundedRect(ctx, trackX, thumbY, 2, thumbHeight, 1, Theme.ACCENT_BRIGHT);
		}

		int buttonY = y + cardHeight - pad - 16;
		int agreeWidth = this.font.width(Theme.bold("I Agree")) + 20;
		int declineWidth = this.font.width(Theme.bold("Decline")) + 20;
		this.tosDecline.set(x + pad, buttonY, declineWidth, 16);
		this.tosAgree.set(x + cardWidth - pad - agreeWidth, buttonY, agreeWidth, 16);

		if (this.tosScrolledBottom) {
			this.pillButton(ctx, this.tosDecline, "Decline", mouseX, mouseY, false);
			this.pillButton(ctx, this.tosAgree, "I Agree", mouseX, mouseY, true);
		} else {
			// Disabled until they read to the bottom, with a hint in the middle.
			this.disabledPill(ctx, this.tosDecline, "Decline");
			this.disabledPill(ctx, this.tosAgree, "I Agree");
			String hint = "Scroll down to continue";
			Theme.text(ctx, this.font, hint, x + (cardWidth - this.font.width(hint)) / 2, buttonY + 4, Theme.TEXT_ASH);
		}
	}

	/** A greyed-out, non-interactive pill for an action that is not available yet. */
	private void disabledPill(GuiGraphics ctx, Rect rect, String label) {
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.SURFACE_CARD);
		String text = Theme.bold(label);
		Theme.text(ctx, this.font, text, rect.x + (rect.width - this.font.width(text)) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.TEXT_ASH);
	}

	private void clickTos(double mouseX, double mouseY) {
		// The buttons do nothing until the terms have been read to the bottom.
		if (!this.tosScrolledBottom) {
			return;
		}

		if (this.tosAgree.contains(mouseX, mouseY)) {
			Settings.acceptTerms();
			this.tosOpen = false;
			Theme.click(1.2F);
			this.maybeStartTutorial();
		} else if (this.tosDecline.contains(mouseX, mouseY)) {
			// Declining withdraws consent and leaves - the service can't be used without it.
			Settings.revokeTerms();
			this.onClose();
		}
	}

	// ------------------------------------------------------------------ first-run tour

	/** The element the current step points at, or null for the centered welcome step. */
	private int @Nullable [] tutorialTarget() {
		return switch (this.tutorialStep) {
			case 1 -> {
				// The left rail, from the first icon to the last.
				int top = this.railRects.isEmpty() ? TOP_BAR_HEIGHT : this.railRects.get(0).y - 3;
				int bottom = this.railRects.isEmpty() ? this.height
						: this.railRects.get(this.railRects.size() - 1).y
								+ this.railRects.get(this.railRects.size() - 1).height + 3;
				yield new int[]{0, top, RAIL_WIDTH, bottom - top};
			}
			// The category chip row.
			case 2 -> new int[]{RAIL_WIDTH, TOP_BAR_HEIGHT, this.width - RAIL_WIDTH, this.chipRowHeight};
			// The search bar in the top bar.
			case 3 -> {
				int searchWidth = this.searchWidth();
				int searchX = this.contentX + (this.contentWidth - searchWidth) / 2;
				int searchY = (TOP_BAR_HEIGHT - 16) / 2;
				yield new int[]{searchX - 2, searchY - 2, searchWidth + 4, 20};
			}
			// The first grid card.
			case 4 -> new int[]{this.contentX, this.gridTop, this.cardWidth, this.cardHeight};
			default -> null;
		};
	}

	private void renderTutorial(GuiGraphics ctx, int mouseX, int mouseY) {
		int[] target = this.tutorialTarget();

		// Spotlight: dim everything except the target with four bands, so the highlighted element stays
		// fully lit. The welcome step has no target, so the whole screen dims.
		if (target == null) {
			ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);
		} else {
			int tx = target[0];
			int ty = target[1];
			int tw = target[2];
			int th = target[3];
			ctx.fill(0, 0, this.width, ty, Theme.SCRIM);
			ctx.fill(0, ty + th, this.width, this.height, Theme.SCRIM);
			ctx.fill(0, ty, tx, ty + th, Theme.SCRIM);
			ctx.fill(tx + tw, ty, this.width, ty + th, Theme.SCRIM);
			Theme.roundedOutline(ctx, tx - 1, ty - 1, tw + 2, th + 2, Theme.RADIUS_CARD, Theme.ACCENT_BRIGHT);
		}

		// The explaining card, kept in the lower-centre so it never covers the element it describes.
		String[] step = TUTORIAL[this.tutorialStep];
		int cardWidth = 260;
		int pad = 12;
		List<String> body = this.wrap(step[1], cardWidth - pad * 2, 4);
		int cardHeight = pad + this.font.lineHeight + 4 + body.size() * (this.font.lineHeight + 1) + 10 + 16 + pad;
		int x = (this.width - cardWidth) / 2;
		int y = this.height - cardHeight - 24;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);

		int ty = y + pad;
		Theme.text(ctx, this.font, Theme.bold(step[0]), x + pad, ty, Theme.TEXT);
		ty += this.font.lineHeight + 4;

		for (String row : body) {
			Theme.text(ctx, this.font, row, x + pad, ty, Theme.TEXT_MUTE);
			ty += this.font.lineHeight + 1;
		}

		// Buttons: Skip subtle on the left, Back (when past step one) and the prominent Next/Done right.
		int buttonY = y + cardHeight - pad - 16;
		boolean last = this.tutorialStep == TUTORIAL.length - 1;
		String nextLabel = last ? "Done" : "Next";
		int nextWidth = this.font.width(Theme.bold(nextLabel)) + 20;
		int skipWidth = this.font.width(Theme.bold("Skip")) + 16;

		this.tutorialSkip.set(x + pad, buttonY, skipWidth, 16);
		this.tutorialNext.set(x + cardWidth - pad - nextWidth, buttonY, nextWidth, 16);
		this.pillButton(ctx, this.tutorialSkip, "Skip", mouseX, mouseY, false);
		this.pillButton(ctx, this.tutorialNext, nextLabel, mouseX, mouseY, true);

		if (this.tutorialStep > 0) {
			int backWidth = this.font.width(Theme.bold("Back")) + 16;
			this.tutorialBack.set(this.tutorialNext.x - 6 - backWidth, buttonY, backWidth, 16);
			this.pillButton(ctx, this.tutorialBack, "Back", mouseX, mouseY, false);
		} else {
			this.tutorialBack.set(0, 0, 0, 0);
		}

		// Progress dots, centred in the gap between Skip and Next, on the button row.
		int dots = TUTORIAL.length;
		int dotGap = 8;
		int dotsWidth = dots * 3 + (dots - 1) * (dotGap - 3);
		int dotX = x + (cardWidth - dotsWidth) / 2;
		int dotY = buttonY + (16 - 3) / 2;

		for (int i = 0; i < dots; i++) {
			Theme.roundedRect(ctx, dotX, dotY, 3, 3, 1, i == this.tutorialStep ? Theme.ACCENT_BRIGHT : Theme.TEXT_ASH);
			dotX += dotGap;
		}
	}

	private void clickTutorial(double mouseX, double mouseY) {
		if (this.tutorialNext.contains(mouseX, mouseY)) {
			Theme.click(1.1F);

			if (this.tutorialStep >= TUTORIAL.length - 1) {
				this.finishTutorial();
			} else {
				this.tutorialStep++;
			}
		} else if (this.tutorialStep > 0 && this.tutorialBack.contains(mouseX, mouseY)) {
			Theme.click(0.9F);
			this.tutorialStep--;
		} else if (this.tutorialSkip.contains(mouseX, mouseY)) {
			Theme.click(0.9F);
			this.finishTutorial();
		}
	}

	private void finishTutorial() {
		this.tutorialActive = false;
		Settings.markTutorialSeen();
	}

	// ------------------------------------------------------------------ chrome

	private void renderTopBar(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, TOP_BAR_HEIGHT, Theme.SURFACE);
		ctx.fill(0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT, Theme.HAIRLINE);

		int searchWidth = this.searchWidth();
		int searchX = this.contentX + (this.contentWidth - searchWidth) / 2;
		int titleX = OUTER_MARGIN;
		int titleRoom = searchX - titleX - 8;

		// "The Schematic" in white, "Index" in the accent, dropping to single scale when cramped.
		String lead = "The Schematic ";
		String tail = "Index";

		if (titleRoom >= this.font.width(Theme.bold(lead + tail)) * 2) {
			Theme.textScaled(ctx, this.font, Theme.bold(lead), titleX, 7, 2.0F, Theme.TEXT);
			Theme.textScaled(ctx, this.font, Theme.bold(tail),
					titleX + this.font.width(Theme.bold(lead)) * 2, 7, 2.0F, Theme.ACCENT_BRIGHT);
		} else if (titleRoom >= this.font.width(lead + tail)) {
			Theme.text(ctx, this.font, Theme.bold(lead), titleX, 12, Theme.TEXT);
			Theme.text(ctx, this.font, Theme.bold(tail), titleX + this.font.width(Theme.bold(lead)), 12, Theme.ACCENT_BRIGHT);
		} else if (titleRoom > 0) {
			Theme.text(ctx, this.font, Theme.bold(tail), titleX, 12, Theme.ACCENT_BRIGHT);
		}

		if (this.gridPage()) {
			int searchY = (TOP_BAR_HEIGHT - 16) / 2;
			boolean focused = this.searchBox.isFocused();
			Theme.roundedRect(ctx, searchX, searchY, searchWidth, 16, Theme.RADIUS_PILL,
					focused ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);

			if (focused) {
				Theme.roundedOutline(ctx, searchX, searchY, searchWidth, 16, Theme.RADIUS_PILL, Theme.ACCENT);
			}
		}

		this.pillButton(ctx, this.closeButton, "Close", mouseX, mouseY, false);
	}

	/** The Browse and Saved pages carry the grid, chip row and search bar; the others do not. */
	private boolean gridPage() {
		return this.page == Page.BROWSE || this.page == Page.SAVED;
	}

	private void renderRail(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, TOP_BAR_HEIGHT, RAIL_WIDTH, this.height, Theme.SURFACE);
		ctx.fill(RAIL_WIDTH - 1, TOP_BAR_HEIGHT, RAIL_WIDTH, this.height, Theme.HAIRLINE);

		Page[] pages = Page.values();

		for (int i = 0; i < pages.length && i < this.railRects.size(); i++) {
			Rect rect = this.railRects.get(i);
			boolean active = pages[i] == this.page;
			boolean hovered = rect.contains(mouseX, mouseY);

			if (active || hovered) {
				ctx.fill(rect.x, rect.y, rect.x + rect.width - 1, rect.y + rect.height, Theme.SURFACE_ELEVATED);
			}

			if (active) {
				ctx.fill(0, rect.y, 2, rect.y + rect.height, Theme.ACCENT);
			}

			int iconX = rect.x + (RAIL_WIDTH - 17) / 2;
			int iconY = rect.y + (RAIL_ITEM_HEIGHT - 16) / 2;

			// The icon tile grows on hover and pops when its tab is selected.
			float hover = Theme.buttonHover(rect, hovered);
			float scale = Theme.popScale(rect, 1.0F + RAIL_HOVER_GROW * hover);
			Theme.pushScale(ctx, iconX - 2, iconY - 2, 20, 20, scale);
			// A lighter tile behind each icon so they read as buttons rather than floating items.
			Theme.roundedRect(ctx, iconX - 2, iconY - 2, 20, 20, Theme.RADIUS_PILL,
					active ? Theme.RAIL_TILE_ACTIVE : Theme.RAIL_TILE);
			ctx.renderItem(pages[i].icon(), iconX, iconY);
			Theme.pop(ctx);

			// Label appears beside the rail on hover, so the icons stay uncluttered.
			if (hovered && !active) {
				String label = pages[i].label;
				int width = this.font.width(label) + 8;
				Theme.roundedRect(ctx, RAIL_WIDTH + 2, rect.y + 8, width, 12, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
				Theme.text(ctx, this.font, label, RAIL_WIDTH + 6, rect.y + 10, Theme.TEXT);
			}
		}
	}

	private void pillButton(GuiGraphics ctx, Rect rect, String label, int mouseX, int mouseY, boolean primary) {
		boolean hovered = rect.contains(mouseX, mouseY);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.buttonScale(rect, 1.0F + Theme.HOVER_SCALE * hover);

		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

		// Hover shifts the fill slightly lighter rather than darker, per the requested feel.
		int fill = Theme.lighten(primary ? Theme.ACCENT : Theme.SURFACE_CARD, 0.12F * hover);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);

		if (hovered && !primary) {
			Theme.roundedOutline(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);
		}

		String text = Theme.bold(Theme.clip(this.font, label, rect.width - 8));
		Theme.text(ctx, this.font, text,
				rect.x + (rect.width - this.font.width(text)) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1,
				primary ? Theme.ON_ACCENT : Theme.TEXT);

		Theme.pop(ctx);
	}

	/** A labelled on/off row: filled accent square when on, hollow when off. */
	private void toggle(GuiGraphics ctx, Rect rect, String label, boolean on, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.buttonScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				Theme.lighten(Theme.SURFACE_CARD, 0.10F * hover));

		int boxSize = 8;
		int boxX = rect.x + 5;
		int boxY = rect.y + (rect.height - boxSize) / 2;

		if (on) {
			Theme.roundedRect(ctx, boxX, boxY, boxSize, boxSize, 1, Theme.ACCENT);
		} else {
			Theme.roundedOutline(ctx, boxX, boxY, boxSize, boxSize, 1, Theme.TEXT_ASH);
		}

		Theme.text(ctx, this.font, Theme.clip(this.font, label, rect.width - boxSize - 14),
				boxX + boxSize + 5, rect.y + (rect.height - this.font.lineHeight) / 2 + 1,
				on ? Theme.TEXT : Theme.TEXT_MUTE);

		Theme.pop(ctx);
	}

	/** A thin track with a draggable knob, value 0..1. Used by the layer control. */
	private void slider(GuiGraphics ctx, Rect rect, float value, int mouseX, int mouseY) {
		float clamped = Math.max(0.0F, Math.min(1.0F, value));
		int trackY = rect.y + rect.height / 2 - 1;
		Theme.roundedRect(ctx, rect.x, trackY, rect.width, 2, 1, Theme.SURFACE_CARD);

		int fill = Math.round(rect.width * clamped);
		Theme.roundedRect(ctx, rect.x, trackY, fill, 2, 1, Theme.ACCENT);

		boolean hovered = rect.contains(mouseX, mouseY);
		int knobX = rect.x + Math.max(3, Math.min(rect.width - 3, fill));
		Theme.roundedRect(ctx, knobX - 3, rect.y + rect.height / 2 - 4, 6, 8, 1,
				hovered || this.draggingLayer ? Theme.ACCENT_BRIGHT : Theme.TEXT);
	}

	/** Save-for-later toggle: filled accent when kept, hollow outline when not. */
	private void saveButton(GuiGraphics ctx, Rect rect, boolean saved, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.buttonScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
		String label = saved ? "Saved" : "Save for later";

		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

		if (saved) {
			Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
					Theme.lighten(Theme.ACCENT, 0.12F * hover));
			Theme.text(ctx, this.font, Theme.bold(label),
					rect.x + (rect.width - this.font.width(Theme.bold(label))) / 2,
					rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.ON_ACCENT);
			Theme.pop(ctx);
			return;
		}

		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				Theme.lighten(Theme.SURFACE_CARD, 0.12F * hover));

		if (hovered) {
			Theme.roundedOutline(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);
		}

		Theme.text(ctx, this.font, Theme.bold(label),
				rect.x + (rect.width - this.font.width(Theme.bold(label))) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.TEXT);

		Theme.pop(ctx);
	}

	/**
	 * The Download button doubles as its own progress bar: a lighter green grows left to right over
	 * the accent fill as real bytes land, and the label counts up. Polled every frame from
	 * {@link Download}, so it tracks the actual transfer rather than playing a fixed animation.
	 */
	private void downloadButton(GuiGraphics ctx, Rect rect, SchematicEntry entry, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.buttonScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				Theme.lighten(Theme.ACCENT, 0.12F * hover));

		Download.Progress progress = Download.progress(entry.id());
		String label = "Download";

		if (progress != null) {
			// Announce a finish or failure once, on the frame the state first changes.
			if (progress.state() != this.downloadStates.get(entry.id())) {
				if (progress.state() == Download.State.DONE) {
					Theme.success();
				} else if (progress.state() == Download.State.FAILED) {
					Theme.failure();
				}

				this.downloadStates.put(entry.id(), progress.state());
			}

			float fraction = switch (progress.state()) {
				case DONE -> 1.0F;
				case RUNNING -> progress.fraction();
				case FAILED -> 0.0F;
			};

			int fillWidth = Math.round((rect.width - 2) * Math.max(0.0F, Math.min(1.0F, fraction)));

			if (fillWidth > 0) {
				Theme.roundedRect(ctx, rect.x + 1, rect.y + 1, fillWidth, rect.height - 2,
						Theme.RADIUS_PILL, Theme.DOWNLOAD_FILL);
			}

			label = switch (progress.state()) {
				case RUNNING -> Math.round(fraction * 100) + "%";
				case DONE -> "Saved";
				case FAILED -> "Retry";
			};
		}

		String text = Theme.bold(label);
		Theme.text(ctx, this.font, text, rect.x + (rect.width - this.font.width(text)) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.ON_ACCENT);

		Theme.pop(ctx);
	}

	private void arrowButton(GuiGraphics ctx, Rect rect, boolean left, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				hovered ? Theme.SURFACE_ELEVATED : 0xCC0F1114);
		Theme.arrow(ctx, rect.x + (rect.width - 4) / 2, rect.y + (rect.height - 7) / 2, left,
				hovered ? Theme.ACCENT_BRIGHT : Theme.TEXT);
	}

	private void renderChipRow(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(RAIL_WIDTH, TOP_BAR_HEIGHT, this.width, TOP_BAR_HEIGHT + this.chipRowHeight, Theme.BACKDROP);

		for (int i = 0; i < this.chipRects.size(); i++) {
			Rect rect = this.chipRects.get(i);
			Category value = this.chipOrder.get(i);
			boolean active = value == this.category;
			boolean hovered = rect.contains(mouseX, mouseY);
			int fill = active ? Theme.ACCENT : (hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);

			float hover = Theme.buttonHover(rect, hovered);
			float scale = Theme.popScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
			Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

			Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);
			Theme.text(ctx, this.font, Theme.bold(value.label()), rect.x + 6,
					rect.y + (CHIP_HEIGHT - this.font.lineHeight) / 2 + 1,
					active ? Theme.ON_ACCENT : Theme.TEXT_MUTE);

			Theme.pop(ctx);
		}

		this.pillButton(ctx, this.sortButton, "Sort: " + this.sort.label(), mouseX, mouseY, false);
	}

	// ------------------------------------------------------------------ grid

	private void renderGrid(GuiGraphics ctx, int mouseX, int mouseY) {
		Catalogue.State state = Catalogue.state();

		if (state != Catalogue.State.READY) {
			this.renderSkeleton(ctx, state == Catalogue.State.OFFLINE, mouseX, mouseY);
			return;
		}

		this.retryButton.set(0, 0, 0, 0);

		if (this.visible.isEmpty()) {
			String message = this.page == Page.SAVED
					? "Nothing saved yet - open a post and Save for later"
					: "Nothing matches that filter";
			Theme.text(ctx, this.font, message,
					this.contentX + (this.contentWidth - this.font.width(message)) / 2,
					this.gridTop + 40, Theme.TEXT_ASH);
			return;
		}

		this.maybeLoadMore();
		int shown = this.shownCap();

		ctx.enableScissor(this.contentX, this.gridTop, this.contentX + this.contentWidth, this.gridBottom);

		int rowHeight = this.cardHeight + GUTTER;
		int firstRow = Math.max(0, (int) (this.scroll / rowHeight));
		int lastRow = Math.min((shown - 1) / this.columns,
				(int) ((this.scroll + (this.gridBottom - this.gridTop)) / rowHeight));

		for (int row = firstRow; row <= lastRow; row++) {
			for (int column = 0; column < this.columns; column++) {
				int index = row * this.columns + column;

				if (index >= shown) {
					break;
				}

				int x = this.contentX + column * (this.cardWidth + GUTTER);
				int y = this.gridTop + row * rowHeight - Math.round(this.scroll);
				this.renderCard(ctx, this.visible.get(index), x, y, mouseX, mouseY);
			}
		}

		// A "loading more" line under the last row while further pages are still to be revealed.
		if (shown < this.visible.size()) {
			int rows = (shown + this.columns - 1) / this.columns;
			int y = this.gridTop + rows * rowHeight - Math.round(this.scroll) + 4;
			String more = "Loading more...";
			Theme.text(ctx, this.font, more, this.contentX + (this.contentWidth - this.font.width(more)) / 2, y,
					Theme.TEXT_ASH);
		}

		ctx.disableScissor();
	}

	/**
	 * Placeholder cards for when the index has not arrived: a shimmer while connecting, and a frozen
	 * version behind a message when the connection failed. Better than an empty screen, and it shows
	 * the shape of what is coming.
	 */
	private void renderSkeleton(GuiGraphics ctx, boolean offline, int mouseX, int mouseY) {
		ctx.enableScissor(this.contentX, this.gridTop, this.contentX + this.contentWidth, this.gridBottom);

		int rowHeight = this.cardHeight + GUTTER;
		int rows = (this.gridBottom - this.gridTop) / rowHeight + 1;
		int imageHeight = imageHeight(this.cardWidth);
		long now = System.currentTimeMillis();

		for (int row = 0; row < rows; row++) {
			for (int column = 0; column < this.columns; column++) {
				int x = this.contentX + column * (this.cardWidth + GUTTER);
				int y = this.gridTop + row * rowHeight;

				Theme.roundedRect(ctx, x, y, this.cardWidth, this.cardHeight, Theme.RADIUS_CARD, Theme.SURFACE_CARD);
				Theme.roundedRect(ctx, x, y, this.cardWidth, imageHeight, Theme.RADIUS_CARD, Theme.SKELETON);
				Theme.roundedRect(ctx, x + 5, y + imageHeight + 6, this.cardWidth * 2 / 3, 5, 1, Theme.SKELETON);
				Theme.roundedRect(ctx, x + 5, y + imageHeight + 15, this.cardWidth / 3, 5, 1, Theme.SKELETON);

				if (!offline) {
					// A band sweeps across the placeholders so it reads as loading, not as broken.
					int span = this.cardWidth + 40;
					int offset = (int) ((now / 4L + (long) (row + column) * 40L) % span) - 20;
					int shimmerX = x + offset;
					ctx.enableScissor(Math.max(this.contentX, x), y,
							Math.min(this.contentX + this.contentWidth, x + this.cardWidth), y + imageHeight);
					Theme.roundedRect(ctx, shimmerX, y, 18, imageHeight, 0, Theme.SKELETON_SHINE);
					ctx.disableScissor();
				}
			}
		}

		ctx.disableScissor();

		if (!offline) {
			this.retryButton.set(0, 0, 0, 0);
			return;
		}

		// Offline: dim the placeholders and say what happened.
		ctx.fill(this.contentX, this.gridTop, this.contentX + this.contentWidth, this.gridBottom, 0xCC0F1114);

		String headline = "Can't reach the index";
		String detail = "Check your connection and try again.";
		int centreX = this.contentX + this.contentWidth / 2;
		int centreY = this.gridTop + (this.gridBottom - this.gridTop) / 2;

		Theme.textScaled(ctx, this.font, Theme.bold(headline),
				centreX - this.font.width(Theme.bold(headline)), centreY - 24, 2.0F, Theme.TEXT);
		Theme.text(ctx, this.font, detail, centreX - this.font.width(detail) / 2, centreY - 2, Theme.TEXT_MUTE);

		int retryWidth = this.font.width(Theme.bold("Try again")) + 20;
		this.retryButton.set(centreX - retryWidth / 2, centreY + 12, retryWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.retryButton, "Try again", mouseX, mouseY, true);
	}

	private void renderCard(GuiGraphics ctx, SchematicEntry entry, int x, int y, int mouseX, int mouseY) {
		boolean hovered = Theme.inside(mouseX, mouseY, x, y, this.cardWidth, this.cardHeight)
				&& mouseY >= this.gridTop && mouseY < this.gridBottom;

		Theme.roundedRect(ctx, x, y, this.cardWidth, this.cardHeight, Theme.RADIUS_CARD, Theme.SURFACE_CARD);

		int imageHeight = imageHeight(this.cardWidth);
		Identifier texture = this.thumbnailTexture(entry);

		if (texture != null) {
			Theme.image(ctx, texture, x, y, this.cardWidth, imageHeight);
		} else {
			Theme.blueprintPlaceholder(ctx, x, y, this.cardWidth, imageHeight);
		}

		String tag = Theme.clip(this.font, entry.category().label(), this.cardWidth - 34);
		int tagWidth = this.font.width(tag) + 8;
		Theme.roundedRect(ctx, x + 4, y + imageHeight - 15, tagWidth, 11, Theme.RADIUS_PILL, 0xCC0F1114);
		Theme.text(ctx, this.font, tag, x + 8, y + imageHeight - 13, Theme.TEXT);

		Rect heart = this.heartRect(x, y, imageHeight);
		Theme.roundedRect(ctx, heart.x - 2, heart.y - 2, HEART_SIZE + 4, HEART_SIZE + 4, Theme.RADIUS_PILL, 0xCC0F1114);
		Theme.heartPopped(ctx, heart.x, heart.y, isLikedBy(entry), popAge(entry));

		if (isSaved(entry)) {
			int badge = this.font.width("Saved") + 8;
			int badgeX = x + this.cardWidth - badge - 4;
			Theme.roundedRect(ctx, badgeX - 1, y + 3, badge + 2, 13, Theme.RADIUS_PILL, 0xFF000000);
			Theme.roundedRect(ctx, badgeX, y + 4, badge, 11, Theme.RADIUS_PILL, Theme.ACCENT);
			Theme.text(ctx, this.font, "Saved", badgeX + 4, y + 6, Theme.ON_ACCENT);
		}

		int textX = x + 5;
		int textWidth = this.cardWidth - 10;
		// clipBold, not clip: the bold format code widens each glyph, so a title measured plain then
		// bolded used to overrun the card. The card shows the short thumbnail name.
		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, entry.cardName(), textWidth)),
				textX, y + imageHeight + 6, Theme.TEXT);

		// Right-aligned download glyph + count, then the poster gets whatever room is left, so a long
		// username can never push the count off the card.
		int metaY = y + imageHeight + 6 + this.font.lineHeight + 2;
		String downloads = entry.downloadsLabel();
		int countWidth = this.font.width(downloads);
		int countX = x + this.cardWidth - 5 - countWidth;
		Theme.text(ctx, this.font, downloads, countX, metaY, Theme.TEXT_MUTE);
		Theme.downloadGlyph(ctx, countX - Theme.DOWNLOAD_GLYPH_WIDTH - 3, metaY + 2, Theme.TEXT_MUTE);

		int posterRoom = countX - Theme.DOWNLOAD_GLYPH_WIDTH - 6 - textX;
		Theme.text(ctx, this.font, Theme.clip(this.font, entry.poster(), posterRoom), textX, metaY, Theme.TEXT_MUTE);

		if (hovered) {
			Theme.roundedOutline(ctx, x, y, this.cardWidth, this.cardHeight, Theme.RADIUS_CARD, Theme.ACCENT_BRIGHT);
		}
	}

	private Rect heartRect(int cardX, int cardY, int imageHeight) {
		Rect rect = new Rect();
		rect.set(cardX + this.cardWidth - HEART_SIZE - 6, cardY + imageHeight - HEART_SIZE - 5, HEART_SIZE, HEART_SIZE);
		return rect;
	}

	private void renderScrollbar(GuiGraphics ctx) {
		if (this.maxScroll <= 0.0F) {
			return;
		}

		int trackHeight = this.gridBottom - this.gridTop;
		int barHeight = Math.max(16, Math.round(trackHeight * (trackHeight / (trackHeight + this.maxScroll))));
		int barY = this.gridTop + Math.round((trackHeight - barHeight) * (this.scroll / this.maxScroll));
		int barX = Math.min(this.contentX + this.contentWidth + 2, this.width - 4);
		Theme.roundedRect(ctx, barX, barY, 3, barHeight, 1, Theme.HAIRLINE);
	}

	// ------------------------------------------------------------------ upload page

	private void renderUpload(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		int formWidth = Math.min(this.contentWidth, 300);
		int formX = this.contentX + (this.contentWidth - formWidth) / 2;
		int y = TOP_BAR_HEIGHT + 18;

		if (!UploaderAccess.unlocked()) {
			Theme.textScaled(ctx, this.font, "Uploader access", formX, y, 1.0F, Theme.TEXT);
			y += 14;

			for (String line : this.wrap("Posting is invite only. Enter the access code you were given "
					+ "to unlock the upload form.", formWidth, 3)) {
				Theme.text(ctx, this.font, line, formX, y, Theme.TEXT_MUTE);
				y += this.font.lineHeight + 1;
			}

			y += 6;
			this.field(ctx, this.codeBox, formX, y, formWidth, mouseX, mouseY, partialTick);
			y += FIELD_HEIGHT + 6;

			this.unlockButton.set(formX, y, 70, FIELD_HEIGHT);
			this.pillButton(ctx, this.unlockButton, "Unlock", mouseX, mouseY, true);
			y += FIELD_HEIGHT + 8;

			if (!this.formStatus.isEmpty()) {
				Theme.text(ctx, this.font, this.formStatus, formX, y, Theme.ACCENT_BRIGHT);
				y += this.font.lineHeight + 6;
			}

			Theme.text(ctx, this.font, UploaderAccess.betaHint(), formX, y, Theme.TEXT_ASH);
			return;
		}

		Theme.text(ctx, this.font, Theme.bold("Signed in as " + UploaderAccess.profile()), formX, y, Theme.TEXT);
		this.signOutButton.set(formX + formWidth - 58, y - 4, 58, FIELD_HEIGHT);
		this.pillButton(ctx, this.signOutButton, "Sign out", mouseX, mouseY, false);
		y += 24;

		// Full schematic name - shown on the post's detail panel. Capped so it can't run under the age.
		Theme.text(ctx, this.font, "Schematic name", formX, y, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, "on the post page",
				formX + formWidth - this.font.width("on the post page"), y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.titleBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 11;

		// Short name for the card. Defaults to the schematic name if left blank.
		Theme.text(ctx, this.font, "Thumbnail name", formX, y, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, "on the card",
				formX + formWidth - this.font.width("on the card"), y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.thumbnailBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 11;

		Theme.text(ctx, this.font, "Designed by", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.designerBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 11;

		Theme.text(ctx, this.font, "Description", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.descriptionBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 13;

		this.formCategoryButton.set(formX, y, formWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.formCategoryButton, "Category: " + this.formCategory.label(), mouseX, mouseY, false);
		y += FIELD_HEIGHT + 13;

		// The schematic itself - the one genuinely required file.
		Theme.text(ctx, this.font, "Schematic file", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;

		this.uploadSchematicButton.set(formX, y, formWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.uploadSchematicButton,
				this.formSchematic == null ? "Choose .litematic" : "Change file", mouseX, mouseY, false);
		y += FIELD_HEIGHT + 4;

		String chosen = this.formSchematic == null
				? "No file chosen"
				: this.formSchematic.getFileName().toString();
		Theme.text(ctx, this.font, Theme.clip(this.font, chosen, formWidth), formX, y,
				this.formSchematic == null ? Theme.TEXT_ASH : Theme.ACCENT_BRIGHT);
		y += this.font.lineHeight + 12;

		// Image picker. Stands in for the web upload form's file picker plus crop step.
		Theme.text(ctx, this.font, "Pictures", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;

		int previewWidth = Math.min(formWidth, 160);
		int previewHeight = imageHeight(previewWidth);
		Identifier texture = this.formPictures.isEmpty()
				? null
				: ImageStore.texture(this.formPictureStart + this.formPicturePreview);

		if (texture != null) {
			Theme.image(ctx, texture, formX, y, previewWidth, previewHeight);
		} else {
			Theme.blueprintPlaceholder(ctx, formX, y, previewWidth, previewHeight);
			String empty = "No pictures selected";
			Theme.text(ctx, this.font, empty, formX + (previewWidth - this.font.width(empty)) / 2,
					y + previewHeight / 2 - 4, Theme.TEXT_ASH);
		}

		if (this.formPictures.size() > 1) {
			this.formImagePrev.set(formX + 2, y + previewHeight / 2 - 8, 12, 16);
			this.formImageNext.set(formX + previewWidth - 14, y + previewHeight / 2 - 8, 12, 16);
			this.arrowButton(ctx, this.formImagePrev, true, mouseX, mouseY);
			this.arrowButton(ctx, this.formImageNext, false, mouseX, mouseY);

			String counter = (this.formPicturePreview + 1) + "/" + this.formPictures.size();
			int counterWidth = this.font.width(counter) + 8;
			Theme.roundedRect(ctx, formX + previewWidth - counterWidth - 3, y + previewHeight - 14,
					counterWidth, 11, Theme.RADIUS_PILL, 0xCC0F1114);
			Theme.text(ctx, this.font, counter, formX + previewWidth - counterWidth + 1,
					y + previewHeight - 12, Theme.TEXT);
		} else {
			this.formImagePrev.set(0, 0, 0, 0);
			this.formImageNext.set(0, 0, 0, 0);
		}

		int sideX = formX + previewWidth + 8;
		int sideWidth = Math.max(80, formWidth - previewWidth - 8);
		this.uploadPicturesButton.set(sideX, y, sideWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.uploadPicturesButton, "Upload Pictures", mouseX, mouseY, false);

		Theme.text(ctx, this.font, this.formPictures.isEmpty() ? "1-5 images" : this.formPictures.size() + " selected",
				sideX, y + FIELD_HEIGHT + 5, Theme.TEXT_ASH);

		this.postButton.set(sideX, y + previewHeight - FIELD_HEIGHT, sideWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.postButton, "Post", mouseX, mouseY, true);
		y += previewHeight + 6;

		if (!this.formStatus.isEmpty()) {
			Theme.text(ctx, this.font, Theme.clip(this.font, this.formStatus, this.contentWidth), formX, y, Theme.ACCENT_BRIGHT);
		}
	}

	private void field(GuiGraphics ctx, EditBox box, int x, int y, int width, int mouseX, int mouseY, float partialTick) {
		boolean focused = box.isFocused();
		Theme.roundedRect(ctx, x, y, width, FIELD_HEIGHT, Theme.RADIUS_PILL,
				focused ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);

		if (focused) {
			Theme.roundedOutline(ctx, x, y, width, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.ACCENT);
		}

		box.setX(x + 6);
		box.setY(y + 4);
		box.setWidth(width - 12);
		box.render(ctx, mouseX, mouseY, partialTick);
	}

	// ------------------------------------------------------------------ settings page

	private void renderSettings(GuiGraphics ctx, int mouseX, int mouseY) {
		int formWidth = Math.min(this.contentWidth, 360);
		int formX = this.contentX + (this.contentWidth - formWidth) / 2;
		int y = TOP_BAR_HEIGHT + 16;

		Theme.textScaled(ctx, this.font, Theme.bold("Settings"), formX, y, 1.5F, Theme.TEXT);
		y += 24;

		y = this.settingRow(ctx, this.soundsToggle, "Sound effects",
				"Toggle on/off sound effects like button clicks when opening menus.",
				Settings.sounds(), formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.overwriteToggle, "Confirm before overwriting",
				"Confirm first when overwriting a file that contains the same name, when downloading "
						+ "a new schematic.", Settings.confirmOverwrite(),
				formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.toastsToggle, "Show toasts",
				"Show the slide-in notification cards in the corner for things like downloads and follows.",
				Settings.toasts(), formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.notificationsToggle, "Creator notifications",
				"Get a toast when a creator you follow posts a new schematic.",
				Settings.notifications(), formX, y, formWidth, mouseX, mouseY);

		// Grid density.
		y += 6;
		Theme.text(ctx, this.font, Theme.bold("Grid density"), formX, y, Theme.TEXT);
		y += this.font.lineHeight + 3;

		for (String row : this.wrap("Changing the grid density decides how many posts can fit on one row, "
				+ "compact provides more posts but smaller thumbnails, while large gives you a great view of "
				+ "the thumbnails.", formWidth, 5)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 1;
		}

		y += 4;
		int densityWidth = this.font.width(Theme.bold("Grid: Comfortable")) + 16;
		this.gridDensityButton.set(formX, y, densityWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.gridDensityButton, "Grid: " + Settings.gridDensityLabel(), mouseX, mouseY, false);

		// Download folder, below the grid density.
		y += FIELD_HEIGHT + 12;
		Theme.text(ctx, this.font, Theme.bold("Download folder"), formX, y, Theme.TEXT);
		y += this.font.lineHeight + 3;

		for (String row : this.wrap("The Download folder is meant to be your Schematic folder where you can "
				+ "easily store or access your schematics in Litematica. It is automatically assigned to this "
				+ "client's schematic folder, but you can change it if you prefer to download to a different "
				+ "location.", formWidth, 6)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 1;
		}

		y += 4;
		for (String row : this.wrap(Settings.downloadDirectory().toString(), formWidth, 2)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_MUTE);
			y += this.font.lineHeight + 1;
		}

		y += 4;
		int changeWidth = this.font.width(Theme.bold("Change")) + 16;
		int openWidth = this.font.width(Theme.bold("Open folder")) + 16;
		this.changeFolderButton.set(formX, y, changeWidth, FIELD_HEIGHT);
		this.openFolderButton.set(formX + changeWidth + 6, y, openWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.changeFolderButton, "Change", mouseX, mouseY, false);
		this.pillButton(ctx, this.openFolderButton, "Open folder", mouseX, mouseY, false);

		if (Settings.hasCustomDownloadDirectory()) {
			int resetWidth = this.font.width(Theme.bold("Reset")) + 16;
			this.resetFolderButton.set(this.openFolderButton.x + openWidth + 6, y, resetWidth, FIELD_HEIGHT);
			this.pillButton(ctx, this.resetFolderButton, "Reset", mouseX, mouseY, false);
		} else {
			this.resetFolderButton.set(0, 0, 0, 0);
		}

		// Cache, at the bottom.
		y += FIELD_HEIGHT + 12;
		Theme.text(ctx, this.font, Theme.bold("Cache"), formX, y, Theme.TEXT);
		y += this.font.lineHeight + 3;

		for (String row : this.wrap("Clearing the cache frees the memory used by loaded thumbnails and 3D "
				+ "previews; they reload when next shown.", formWidth, 4)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 1;
		}

		y += 4;
		int clearWidth = this.font.width(Theme.bold("Clear cache")) + 16;
		this.clearCacheButton.set(formX, y, clearWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.clearCacheButton, "Clear cache", mouseX, mouseY, false);

		// Terms of service.
		y += FIELD_HEIGHT + 12;
		Theme.text(ctx, this.font, Theme.bold("Terms of service"), formX, y, Theme.TEXT);
		y += this.font.lineHeight + 3;

		for (String row : this.wrap("Review the terms you agreed to, or withdraw your agreement - which "
				+ "disables the online features.", formWidth, 3)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 1;
		}

		y += 4;
		int termsWidth = this.font.width(Theme.bold("Review terms")) + 16;
		this.termsButton.set(formX, y, termsWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.termsButton, "Review terms", mouseX, mouseY, false);
	}

	private int settingRow(GuiGraphics ctx, Rect rect, String label, String hint, boolean on,
			int x, int y, int width, int mouseX, int mouseY) {
		rect.set(x, y, width, FIELD_HEIGHT);
		this.toggle(ctx, rect, label, on, mouseX, mouseY);
		y += FIELD_HEIGHT + 3;

		for (String row : this.wrap(hint, width, 2)) {
			Theme.text(ctx, this.font, row, x, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 1;
		}

		return y + 8;
	}

	// ------------------------------------------------------------------ news page

	private void renderNews(GuiGraphics ctx) {
		int top = TOP_BAR_HEIGHT + 10;
		int bottom = this.height - OUTER_MARGIN;
		int x = this.contentX;
		int width = this.contentWidth;

		ctx.enableScissor(x, top, x + width, bottom);
		int y = top - Math.round(this.scroll);
		int total = 0;

		for (NewsFeed.Entry entry : NewsFeed.entries()) {
			int cardHeight = this.renderNewsCard(ctx, entry, x, y, width, top, bottom);
			y += cardHeight + 8;
			total += cardHeight + 8;
		}

		ctx.disableScissor();

		this.maxScroll = Math.max(0.0F, total - (bottom - top));
		this.scroll = Math.min(this.scroll, this.maxScroll);

		if (this.maxScroll > 0.0F) {
			int trackHeight = bottom - top;
			int barHeight = Math.max(16, Math.round(trackHeight * (trackHeight / (trackHeight + this.maxScroll))));
			int barY = top + Math.round((trackHeight - barHeight) * (this.scroll / this.maxScroll));
			int barX = Math.min(x + width + 2, this.width - 4);
			Theme.roundedRect(ctx, barX, barY, 3, barHeight, 1, Theme.HAIRLINE);
		}
	}

	/** Draws one news card and returns its height, whether or not it was on screen. */
	private int renderNewsCard(GuiGraphics ctx, NewsFeed.Entry entry, int x, int y, int width,
			int clipTop, int clipBottom) {
		int pad = 10;

		List<String> body = new ArrayList<>();

		for (String paragraph : entry.lines()) {
			body.addAll(this.wrap(paragraph, width - pad * 2, 8));
			body.add("");
		}

		if (!body.isEmpty() && body.get(body.size() - 1).isEmpty()) {
			body.remove(body.size() - 1);
		}

		int headerHeight = 14;
		int cardHeight = pad + headerHeight + 4 + this.font.lineHeight + 4
				+ body.size() * (this.font.lineHeight + 2) + pad;

		// Off-screen cards still count toward the scroll height, they just skip drawing.
		if (y + cardHeight < clipTop || y > clipBottom) {
			return cardHeight;
		}

		Theme.roundedRect(ctx, x, y, width, cardHeight, Theme.RADIUS_CARD, Theme.SURFACE_CARD);

		String badge = entry.badge();
		int badgeWidth = this.font.width(Theme.bold(badge)) + 12;
		Theme.roundedRect(ctx, x + pad, y + pad, badgeWidth, 12, Theme.RADIUS_PILL,
				entry.highlight() ? Theme.ACCENT : Theme.SURFACE_ELEVATED);
		Theme.text(ctx, this.font, Theme.bold(badge), x + pad + 6, y + pad + 2,
				entry.highlight() ? Theme.ON_ACCENT : Theme.TEXT_MUTE);

		String when = entry.when();
		Theme.text(ctx, this.font, when, x + width - pad - this.font.width(when), y + pad + 2, Theme.TEXT_ASH);

		int textY = y + pad + headerHeight + 4;
		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, entry.title(), width - pad * 2)),
				x + pad, textY, Theme.TEXT);
		textY += this.font.lineHeight + 4;

		for (String line : body) {
			if (!line.isEmpty()) {
				Theme.text(ctx, this.font, line, x + pad, textY, Theme.TEXT_MUTE);
			}

			textY += this.font.lineHeight + 2;
		}

		return cardHeight;
	}

	// ------------------------------------------------------------------ detail modal

	private void renderDetail(GuiGraphics ctx, int mouseX, int mouseY) {
		SchematicEntry entry = this.detail;

		if (entry == null) {
			return;
		}

		// The scrim fades in rather than snapping, which takes the hard edge off opening a post.
		long open = System.currentTimeMillis() - this.detailOpenedAt;
		float fade = Math.min(1.0F, Math.max(0.0F, open / 140.0F));
		ctx.fill(0, 0, this.width, this.height, ((int) (0x99 * fade) << 24));

		// Post menus run larger across the board, and the picture and model views each take a further
		// quarter again - the render earns the extra room, the metadata column does not need it.
		int modalWidth = Math.min(this.contentWidth, this.detailModel ? 688 : 550);
		int modalHeight = Math.min(this.height - 24, this.detailModel ? 362 : 290);
		int x = (this.width - modalWidth) / 2;
		int y = (this.height - modalHeight) / 2;
		int pad = 12;

		Theme.roundedRect(ctx, x, y, modalWidth, modalHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);

		int imageWidth = Math.round((modalWidth - pad * 3) * (this.detailModel ? 0.64F : 0.56F));
		int imageHeight = imageHeight(imageWidth);

		if (this.detailModel) {
			SchematicPreview.request(entry.schematicSlot(), this.detailYaw, this.detailPitch, this.detailZoom,
					this.cutaway, this.freeLook, this.freeEye, this.detailLayer);
			Identifier model = SchematicPreview.texture(entry.schematicSlot());

			if (model != null) {
				ctx.fill(x + pad, y + pad, x + pad + imageWidth, y + pad + imageHeight, 0xFF10151A);
				Theme.image(ctx, model, x + pad, y + pad, imageWidth, imageHeight,
						SchematicPreview.WIDTH, SchematicPreview.HEIGHT);
			} else {
				Theme.blueprintPlaceholder(ctx, x + pad, y + pad, imageWidth, imageHeight);
				String message = SchematicPreview.count() == 0 ? "No schematic files found" : "Rendering...";
				Theme.text(ctx, this.font, message,
						x + pad + (imageWidth - this.font.width(message)) / 2,
						y + pad + imageHeight / 2 - 4, Theme.TEXT_MUTE);
			}
		} else {
			Identifier texture = this.imageTexture(entry, this.detailImage);

			if (texture != null) {
				Theme.image(ctx, texture, x + pad, y + pad, imageWidth, imageHeight);
			} else {
				Theme.blueprintPlaceholder(ctx, x + pad, y + pad, imageWidth, imageHeight);
			}
		}

		this.detailImageRect.set(x + pad, y + pad, imageWidth, imageHeight);

		// Arrows are for the picture gallery only. In model view the image itself is the control:
		// drag it to spin the build.
		if (!this.detailModel && entry.imageCount() > 1) {
			this.detailPrev.set(x + pad + 2, y + pad + imageHeight / 2 - 8, 12, 16);
			this.detailNext.set(x + pad + imageWidth - 14, y + pad + imageHeight / 2 - 8, 12, 16);
			this.arrowButton(ctx, this.detailPrev, true, mouseX, mouseY);
			this.arrowButton(ctx, this.detailNext, false, mouseX, mouseY);
		} else {
			this.detailPrev.set(0, 0, 0, 0);
			this.detailNext.set(0, 0, 0, 0);
		}

		String caption = this.detailModel
				? Theme.clip(this.font, SchematicPreview.name(entry.schematicSlot()), imageWidth - 16)
				: (entry.imageCount() > 1 ? (this.detailImage + 1) + "/" + entry.imageCount() : "");

		if (!caption.isEmpty()) {
			int captionWidth = this.font.width(caption) + 8;
			Theme.roundedRect(ctx, x + pad + imageWidth - captionWidth - 3, y + pad + imageHeight - 14,
					captionWidth, 11, Theme.RADIUS_PILL, 0xCC0F1114);
			Theme.text(ctx, this.font, caption, x + pad + imageWidth - captionWidth + 1,
					y + pad + imageHeight - 12, Theme.TEXT);
		}

		int infoX = x + pad + imageWidth + pad;
		int infoWidth = modalWidth - (infoX - x) - pad;
		int line = y + pad;

		// Age sits in the corner, right of the title.
		String age = entry.agoLabel();
		int ageWidth = this.font.width(age);
		Theme.text(ctx, this.font, age, x + modalWidth - pad - ageWidth, y + pad, Theme.TEXT_ASH);

		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, entry.title(), infoWidth - ageWidth - 6)),
				infoX, line, Theme.TEXT);
		line += this.font.lineHeight + 4;
		// Compact follow pill at the right end of the "Posted by" line. "Following" fills accent; the
		// "Follow" and "Unfollow?" confirm states are outlined so they read differently.
		if (this.followConfirm && System.currentTimeMillis() - this.followConfirmAt > 3000L) {
			this.followConfirm = false;
		}

		boolean following = Follows.isFollowing(entry.poster());
		String followLabel = !following ? "Follow" : (this.followConfirm ? "Unfollow?" : "Following");
		int followWidth = this.font.width(Theme.bold(followLabel)) + 12;
		this.detailFollow.set(infoX + infoWidth - followWidth, line - 1, followWidth, 12);
		this.pillButton(ctx, this.detailFollow, followLabel, mouseX, mouseY, following && !this.followConfirm);

		Theme.text(ctx, this.font, Theme.clip(this.font, "Posted by " + entry.poster(), infoWidth - followWidth - 6),
				infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 3;

		Theme.text(ctx, this.font, Theme.clip(this.font, "Designed by " + entry.designer(), infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 6;

		line = this.metaRow(ctx, "Dimensions", entry.dimensionsLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Total Blocks", entry.blockCountLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Volume", entry.volumeLabel(), infoX, line, infoWidth);

		Theme.text(ctx, this.font, "Downloads", infoX, line, Theme.TEXT_ASH);
		String downloads = entry.downloadsLabel();
		int downloadsWidth = this.font.width(downloads);
		Theme.text(ctx, this.font, downloads, infoX + infoWidth - downloadsWidth, line, Theme.TEXT);
		Theme.downloadGlyph(ctx, infoX + infoWidth - downloadsWidth - Theme.DOWNLOAD_GLYPH_WIDTH - 3,
				line + 2, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 2;

		boolean liked = isLikedBy(entry);
		String likeCount = SchematicEntry.compact(likesOf(entry));
		Theme.text(ctx, this.font, "Likes", infoX, line, Theme.TEXT_ASH);
		int likeWidth = this.font.width(likeCount);
		Theme.text(ctx, this.font, likeCount, infoX + infoWidth - likeWidth, line, liked ? Theme.ACCENT_BRIGHT : Theme.TEXT);
		this.detailHeart.set(infoX + infoWidth - likeWidth - HEART_SIZE - 4, line - 1, HEART_SIZE, HEART_SIZE);
		Theme.heartPopped(ctx, this.detailHeart.x, this.detailHeart.y, liked, popAge(entry));
		line += this.font.lineHeight + 2;

		if (this.detailModel) {
			line += 6;
			Theme.text(ctx, this.font, "View", infoX, line, Theme.TEXT_ASH);
			line += this.font.lineHeight + 3;

			this.cutawayToggle.set(infoX, line, infoWidth, FIELD_HEIGHT);
			this.toggle(ctx, this.cutawayToggle, "Zoom through walls", this.cutaway, mouseX, mouseY);
			line += FIELD_HEIGHT + 4;

			this.freeLookToggle.set(infoX, line, infoWidth, FIELD_HEIGHT);
			this.toggle(ctx, this.freeLookToggle, "Look around", this.freeLook, mouseX, mouseY);
			line += FIELD_HEIGHT + 6;

			// Layer slider: peel off the top of the build to look inside without moving the camera. The
			// count is real block layers of the model being shown, 1 to its Y height.
			int totalLayers = this.detailLayerHeight();
			int shownLayers = Math.max(1, Math.min(totalLayers, Math.round(this.detailLayer * totalLayers)));
			String layerLabel = shownLayers + " / " + totalLayers;
			Theme.text(ctx, this.font, "Layers", infoX, line, Theme.TEXT_ASH);
			Theme.text(ctx, this.font, layerLabel, infoX + infoWidth - this.font.width(layerLabel), line, Theme.TEXT);
			line += this.font.lineHeight + 3;
			this.layerSlider.set(infoX, line, infoWidth, 10);
			this.slider(ctx, this.layerSlider, this.detailLayer, mouseX, mouseY);
			line += 10 + 6;

			this.resetViewButton.set(infoX, line, infoWidth, FIELD_HEIGHT);
			this.pillButton(ctx, this.resetViewButton, "Reset view", mouseX, mouseY, false);
		} else {
			this.cutawayToggle.set(0, 0, 0, 0);
			this.freeLookToggle.set(0, 0, 0, 0);
			this.layerSlider.set(0, 0, 0, 0);
			this.resetViewButton.set(0, 0, 0, 0);
		}

		int descriptionY = y + pad + imageHeight + 6;

		for (String row : this.wrap(entry.description(), modalWidth - pad * 2, 2)) {
			Theme.text(ctx, this.font, row, x + pad, descriptionY, Theme.TEXT_MUTE);
			descriptionY += this.font.lineHeight + 1;
		}

		if (!this.status.isEmpty()) {
			Theme.text(ctx, this.font, Theme.clip(this.font, this.status, modalWidth - pad * 2),
					x + pad, descriptionY + 2, Theme.ACCENT_BRIGHT);
		}

		int buttonY = y + modalHeight - pad - 16;
		// A little wider than the label so the progress fill and its percentage have room.
		int downloadWidth = this.font.width(Theme.bold("Downloading")) + 18;
		String previewLabel = this.detailModel ? "Pictures" : "3D preview";
		int previewWidth = this.font.width(Theme.bold(previewLabel)) + 18;
		int closeWidth = this.font.width(Theme.bold("Close")) + 18;
		String saveLabel = isSaved(entry) ? "Saved" : "Save for later";
		int saveWidth = this.font.width(Theme.bold(saveLabel)) + 18;

		this.detailClose.set(x + pad, buttonY, closeWidth, 16);
		this.detailSave.set(this.detailClose.x + closeWidth + 6, buttonY, saveWidth, 16);
		this.detailDownload.set(x + modalWidth - pad - downloadWidth, buttonY, downloadWidth, 16);
		this.detailPreview3d.set(this.detailDownload.x - 6 - previewWidth, buttonY, previewWidth, 16);

		this.pillButton(ctx, this.detailClose, "Close", mouseX, mouseY, false);
		this.saveButton(ctx, this.detailSave, isSaved(entry), mouseX, mouseY);
		this.pillButton(ctx, this.detailPreview3d, previewLabel, mouseX, mouseY, false);
		this.downloadButton(ctx, this.detailDownload, entry, mouseX, mouseY);

		// A small boxed X straddling the top-right corner, the close affordance people expect. Drawn
		// last so it sits above the modal edge; scales on hover like the other buttons.
		int cornerSize = 14;
		this.detailCornerClose.set(x + modalWidth - cornerSize / 2, y - cornerSize / 2, cornerSize, cornerSize);
		boolean cornerHover = this.detailCornerClose.contains(mouseX, mouseY);
		float cornerScale = Theme.buttonScale(this.detailCornerClose,
				1.0F + Theme.HOVER_SCALE * Theme.buttonHover(this.detailCornerClose, cornerHover));
		Theme.pushScale(ctx, this.detailCornerClose.x, this.detailCornerClose.y, cornerSize, cornerSize, cornerScale);
		Theme.roundedRect(ctx, this.detailCornerClose.x, this.detailCornerClose.y, cornerSize, cornerSize,
				Theme.RADIUS_CARD, cornerHover ? Theme.ACCENT : Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, this.detailCornerClose.x, this.detailCornerClose.y, cornerSize, cornerSize,
				Theme.RADIUS_CARD, Theme.HAIRLINE);
		Theme.cross(ctx, this.detailCornerClose.x + 4, this.detailCornerClose.y + 4, 6,
				cornerHover ? Theme.ON_ACCENT : Theme.TEXT_MUTE);
		Theme.pop(ctx);

		// A report flag button straddling the bottom-left corner of the modal.
		int flagSize = 22;
		this.detailReport.set(x - flagSize / 2, y + modalHeight - flagSize / 2, flagSize, flagSize);
		boolean flagHover = this.detailReport.contains(mouseX, mouseY);
		float flagScale = Theme.buttonScale(this.detailReport,
				1.0F + Theme.HOVER_SCALE * Theme.buttonHover(this.detailReport, flagHover));
		Theme.pushScale(ctx, this.detailReport.x, this.detailReport.y, flagSize, flagSize, flagScale);
		Theme.roundedRect(ctx, this.detailReport.x, this.detailReport.y, flagSize, flagSize,
				Theme.RADIUS_CARD, flagHover ? Theme.ACCENT : Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, this.detailReport.x, this.detailReport.y, flagSize, flagSize,
				Theme.RADIUS_CARD, flagHover ? Theme.ACCENT_BRIGHT : Theme.HAIRLINE);
		Theme.flag(ctx, this.detailReport.x + 7, this.detailReport.y + 6, 10,
				flagHover ? Theme.ON_ACCENT : Theme.TEXT);
		Theme.pop(ctx);

		// Tooltip so it is clear what the flag does.
		if (flagHover) {
			String tip = "Report this Post";
			int tipWidth = this.font.width(tip) + 8;
			int tipX = this.detailReport.x + flagSize + 3;
			int tipY = this.detailReport.y + (flagSize - 12) / 2;
			Theme.roundedRect(ctx, tipX, tipY, tipWidth, 12, Theme.RADIUS_PILL, 0xF00F1114);
			Theme.text(ctx, this.font, tip, tipX + 4, tipY + 2, Theme.TEXT);
		}
	}

	/** The reason picker shown after the report flag is tapped. Modal-over-modal, like the confirm card. */
	private void renderReportPicker(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 14;
		int rowHeight = 16;
		int reasonGap = 5;
		int cardWidth = 232;
		int line = this.font.lineHeight;
		List<String> warning = this.wrap(
				"Only report posts that break the rules. False reports are taken seriously and can get "
						+ "your account banned from the Index.", cardWidth - pad * 2, 4);

		// Spacing constants, so the card height and the layout below stay in step.
		int titleToWarning = 12;
		int warningToReasons = 16;
		int reasonsToDivider = 10;
		int dividerToCancel = 10;
		int warnHeight = warning.size() * (line + 1);
		int reasonsHeight = REPORT_REASONS.length * rowHeight + (REPORT_REASONS.length - 1) * reasonGap;

		int cardHeight = pad + line + titleToWarning + warnHeight + warningToReasons + reasonsHeight
				+ reasonsToDivider + 1 + dividerToCancel + rowHeight + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold("Report this post"), x + pad, y + pad, Theme.TEXT);

		int warnY = y + pad + line + titleToWarning;

		for (String row : warning) {
			Theme.text(ctx, this.font, row, x + pad, warnY, Theme.ACCENT_BRIGHT);
			warnY += line + 1;
		}

		int rowY = warnY - 1 + warningToReasons;

		for (int i = 0; i < REPORT_REASONS.length; i++) {
			if (this.reportReasonRects[i] == null) {
				this.reportReasonRects[i] = new Rect();
			}

			this.reportReasonRects[i].set(x + pad, rowY, cardWidth - pad * 2, rowHeight);
			this.pillButton(ctx, this.reportReasonRects[i], REPORT_REASONS[i], mouseX, mouseY, false);
			rowY += rowHeight + reasonGap;
		}

		// A divider so Cancel reads as a separate action, not one more reason.
		rowY += reasonsToDivider - reasonGap;
		ctx.fill(x + pad, rowY, x + cardWidth - pad, rowY + 1, Theme.HAIRLINE);
		rowY += 1 + dividerToCancel;

		this.reportCancel.set(x + pad, rowY, cardWidth - pad * 2, rowHeight);
		this.pillButton(ctx, this.reportCancel, "Cancel", mouseX, mouseY, false);
	}

	/** Wraps text that contains explicit line breaks; a blank source line becomes a blank spacer line. */
	private List<String> wrapParagraphs(String text, int width) {
		List<String> out = new ArrayList<>();

		for (String paragraph : text.split("\n")) {
			if (paragraph.isEmpty()) {
				out.add("");
			} else {
				out.addAll(this.wrapContext(paragraph, width));
			}
		}

		return out;
	}

	/**
	 * Wraps to a fixed width like a text editor: it prefers to break at a space, but a run too long to
	 * fit on a line on its own is broken mid-word, so text can never spill past the box.
	 */
	private List<String> wrapContext(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (int i = 0; i < text.length(); i++) {
			current.append(text.charAt(i));

			if (this.font.width(current.toString()) <= width) {
				continue;
			}

			// Overflowed this line: back up to the last space if there is one, else hard-break.
			int lastSpace = current.lastIndexOf(" ");

			if (lastSpace > 0) {
				lines.add(current.substring(0, lastSpace));
				current = new StringBuilder(current.substring(lastSpace + 1));
			} else {
				char carried = current.charAt(current.length() - 1);
				lines.add(current.substring(0, current.length() - 1));
				current = new StringBuilder().append(carried);
			}
		}

		lines.add(current.toString());
		return lines;
	}

	/**
	 * The second report step: a "Context" box the reporter can type a short explanation into. The box
	 * and the card both grow downward as the text wraps onto more lines.
	 */
	private void renderReportContext(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 14;
		int line = this.font.lineHeight;
		int cardWidth = 236;
		int boxInner = cardWidth - pad * 2 - 12;

		// Wrap the current text to know how tall the box needs to be (grows with the number of lines).
		List<String> lines = this.wrapContext(this.reportContext, boxInner);
		int textLines = Math.max(1, lines.size());
		int boxHeight = 8 + textLines * (line + 1) + 6;

		int cardHeight = pad + line + 4 + line + 8 + boxHeight + 6 + line + 10 + 16 + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);

		int ty = y + pad;
		Theme.text(ctx, this.font, Theme.bold("Context"), x + pad, ty, Theme.TEXT);
		ty += line + 4;
		Theme.text(ctx, this.font, "Explain the report - under 100 characters.", x + pad, ty, Theme.TEXT_ASH);
		ty += line + 8;

		// The growing input box.
		int boxX = x + pad;
		int boxWidth = cardWidth - pad * 2;
		Theme.roundedRect(ctx, boxX, ty, boxWidth, boxHeight, Theme.RADIUS_PILL, Theme.BACKDROP);
		Theme.roundedOutline(ctx, boxX, ty, boxWidth, boxHeight, Theme.RADIUS_PILL, Theme.ACCENT);

		int textX = boxX + 6;
		int textY = ty + 6;

		if (this.reportContext.isEmpty()) {
			Theme.text(ctx, this.font, "Type here...", textX, textY, Theme.TEXT_ASH);
		} else {
			for (String row : lines) {
				Theme.text(ctx, this.font, row, textX, textY, Theme.TEXT);
				textY += line + 1;
			}
		}

		// Blinking caret at the end of the last line.
		if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
			String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
			int caretX = this.reportContext.isEmpty() ? textX : textX + this.font.width(last);
			int caretY = this.reportContext.isEmpty() ? ty + 6 : ty + 6 + (textLines - 1) * (line + 1);
			ctx.fill(caretX, caretY - 1, caretX + 1, caretY + line, Theme.TEXT);
		}

		ty += boxHeight + 6;
		String counter = this.reportContext.length() + "/" + REPORT_CONTEXT_MAX;
		Theme.text(ctx, this.font, counter, x + cardWidth - pad - this.font.width(counter), ty, Theme.TEXT_ASH);
		ty += line + 10;

		this.reportSubmit.set(x + pad, ty, cardWidth - pad * 2, 16);
		this.pillButton(ctx, this.reportSubmit, "Submit report", mouseX, mouseY, true);
	}

	/**
	 * A small confirm card over the detail modal, shown when a download would replace a file the
	 * player already has. Modal-over-modal: it darkens the rest and captures clicks until answered.
	 */
	private void renderOverwriteConfirm(GuiGraphics ctx, int mouseX, int mouseY) {
		SchematicEntry entry = this.pendingOverwrite;

		if (entry == null) {
			return;
		}

		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int cardWidth = 240;
		int cardHeight = 96;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);

		int pad = 12;
		Theme.text(ctx, this.font, Theme.bold("Replace existing file?"), x + pad, y + pad, Theme.TEXT);

		int textY = y + pad + this.font.lineHeight + 4;
		// Show the actual saved file name - the post's full title, which is what the download uses.
		String name = Theme.clip(this.font, entry.title() + ".litematic", cardWidth - pad * 2);
		Theme.text(ctx, this.font, name, x + pad, textY, Theme.ACCENT_BRIGHT);

		List<String> lines = this.wrap("A schematic with this name is already in your download folder.",
				cardWidth - pad * 2, 2);
		int lineY = textY + this.font.lineHeight + 4;

		for (String line : lines) {
			Theme.text(ctx, this.font, line, x + pad, lineY, Theme.TEXT_MUTE);
			lineY += this.font.lineHeight + 1;
		}

		int buttonY = y + cardHeight - pad - 16;
		int buttonWidth = (cardWidth - pad * 2 - 6) / 2;
		this.overwriteCancel.set(x + pad, buttonY, buttonWidth, 16);
		this.overwriteReplace.set(x + pad + buttonWidth + 6, buttonY, buttonWidth, 16);

		this.pillButton(ctx, this.overwriteCancel, "Cancel", mouseX, mouseY, false);
		this.pillButton(ctx, this.overwriteReplace, "Replace", mouseX, mouseY, true);
	}

	private int metaRow(GuiGraphics ctx, String label, String value, int x, int y, int width) {
		Theme.text(ctx, this.font, label, x, y, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, value, x + width - this.font.width(value), y, Theme.TEXT);
		return y + this.font.lineHeight + 2;
	}

	private List<String> wrap(String text, int width, int maxLines) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;

			if (this.font.width(candidate) > width) {
				lines.add(current.toString());
				current = new StringBuilder(word);

				if (lines.size() == maxLines) {
					return lines;
				}
			} else {
				current = new StringBuilder(candidate);
			}
		}

		if (!current.isEmpty() && lines.size() < maxLines) {
			lines.add(current.toString());
		}

		return lines;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		this.registerButtonPress(mouseX, mouseY);

		// The terms gate is absolute: nothing else responds until it is answered.
		if (this.tosOpen) {
			this.clickTos(mouseX, mouseY);
			return true;
		}

		// The tour owns the screen while it is up: only its own buttons respond.
		if (this.tutorialActive) {
			this.clickTutorial(mouseX, mouseY);
			return true;
		}

		if (this.detail != null) {
			if (this.pendingOverwrite != null) {
				this.clickOverwriteConfirm(mouseX, mouseY);
				return true;
			}

			if (this.reportOpen) {
				this.clickReportPicker(mouseX, mouseY);
				return true;
			}

			if (this.reportContextOpen) {
				this.clickReportContext(mouseX, mouseY);
				return true;
			}

			if (this.detailModel && this.layerSlider.contains(mouseX, mouseY)) {
				this.draggingLayer = true;
				this.setLayerFromMouse(mouseX);
				return true;
			}

			if (this.detailModel && this.detailImageRect.contains(mouseX, mouseY)) {
				this.orbiting = true;
			}

			this.clickDetail(mouseX, mouseY);
			return true;
		}

		if (this.closeButton.contains(mouseX, mouseY)) {
			this.onClose();
			return true;
		}

		for (int i = 0; i < this.railRects.size(); i++) {
			if (this.railRects.get(i).contains(mouseX, mouseY)) {
				Theme.buttonPop(this.railRects.get(i));
				this.switchPage(Page.values()[i]);
				return true;
			}
		}

		if (this.page == Page.UPLOAD) {
			return this.clickUpload(event, doubleClick, mouseX, mouseY);
		}

		if (this.page == Page.SETTINGS) {
			return this.clickSettings(mouseX, mouseY);
		}

		// The News tab is read-only; its cards are not clickable, so swallow clicks here rather than
		// letting them fall through to the grid's entry hit-testing.
		if (this.page == Page.NEWS) {
			return true;
		}

		if (this.retryButton.contains(mouseX, mouseY)) {
			Theme.click();
			Catalogue.refresh();
			return true;
		}

		if (this.sortButton.contains(mouseX, mouseY)) {
			this.sort = this.sort.next();
			Theme.click();
			this.layoutChips();
			this.refilter();
			return true;
		}

		for (int i = 0; i < this.chipRects.size(); i++) {
			if (this.chipRects.get(i).contains(mouseX, mouseY)) {
				Theme.buttonPop(this.chipRects.get(i));
				this.category = this.chipOrder.get(i);
				Theme.click();
				this.scroll = 0.0F;
				this.refilter();
				return true;
			}
		}

		SchematicEntry hit = this.entryAt(mouseX, mouseY);

		if (hit != null) {
			if (this.heartAt(hit, mouseX, mouseY)) {
				toggleLike(hit);

				if (this.page == Page.SAVED) {
					this.refilter();
				}
			} else {
				this.detail = hit;
				// Decode the whole gallery up front so flipping through it never stalls; the textures
				// stay cached, so coming back to this post is instant too.
				if (hit.imageUrls().isEmpty()) {
					ImageStore.preload(hit.imageStart(), hit.imageCount());
				} else {
					ImageStore.preload(hit.imageUrls());
				}
				Theme.click(1.2F);
				this.detailOpenedAt = System.currentTimeMillis();
				this.detailImage = 0;
				this.detailModel = false;
				this.detailYaw = 35.0F;
				this.detailPitch = 28.0F;
				this.detailZoom = 1.0F;
				this.detailLayer = 1.0F;
				this.followConfirm = false;
				this.pendingOverwrite = null;
				this.reportOpen = false;
				this.reportContextOpen = false;
				this.status = "";
			}

			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void switchPage(Page target) {
		if (target != this.page) {
			// Tabs chime like amethyst so navigating feels different from pressing a button.
			Theme.tab();
		}

		this.page = target;
		this.scroll = 0.0F;
		this.formStatus = "";
		this.setFocused(null);
		this.layoutChips();
		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.refilter();
	}

	private boolean clickUpload(MouseButtonEvent event, boolean doubleClick, double mouseX, double mouseY) {
		if (!UploaderAccess.unlocked()) {
			if (this.unlockButton.contains(mouseX, mouseY)) {
				// The code is checked against the server, so do it off-thread and report back.
				String value = this.codeBox.getValue();
				this.formStatus = "Checking code...";
				this.setFocused(null);
				Thread worker = new Thread(() -> {
					String owner = UploaderAccess.redeem(value);
					Minecraft.getInstance().execute(() ->
							this.formStatus = owner == null ? "That code is not valid." : "Unlocked as " + owner + ".");
				}, "schematicindex-code");
				worker.setDaemon(true);
				worker.start();
				return true;
			}

			return this.focusField(this.codeBox, event, doubleClick, mouseX, mouseY);
		}

		if (this.signOutButton.contains(mouseX, mouseY)) {
			UploaderAccess.signOut();
			this.formStatus = "";
			this.setFocused(null);
			return true;
		}

		if (this.formCategoryButton.contains(mouseX, mouseY)) {
			Category[] tags = Category.tags();
			int index = 0;

			for (int i = 0; i < tags.length; i++) {
				if (tags[i] == this.formCategory) {
					index = i;
					break;
				}
			}

			this.formCategory = tags[(index + 1) % tags.length];
			return true;
		}

		if (this.formImagePrev.contains(mouseX, mouseY)) {
			this.formPicturePreview = Math.floorMod(this.formPicturePreview - 1, Math.max(1, this.formPictures.size()));
			return true;
		}

		if (this.formImageNext.contains(mouseX, mouseY)) {
			this.formPicturePreview = Math.floorMod(this.formPicturePreview + 1, Math.max(1, this.formPictures.size()));
			return true;
		}

		if (this.uploadPicturesButton.contains(mouseX, mouseY)) {
			this.openPicturePicker();
			return true;
		}

		if (this.uploadSchematicButton.contains(mouseX, mouseY)) {
			this.openSchematicPicker();
			return true;
		}

		if (this.postButton.contains(mouseX, mouseY)) {
			this.submitPost();
			return true;
		}

		return this.focusField(this.titleBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.thumbnailBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.designerBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.descriptionBox, event, doubleClick, mouseX, mouseY);
	}

	private boolean clickSettings(double mouseX, double mouseY) {
		if (this.soundsToggle.contains(mouseX, mouseY)) {
			Settings.toggleSounds();
			// Plays only if sound is now on, which is its own confirmation.
			Theme.click(1.2F);
		} else if (this.overwriteToggle.contains(mouseX, mouseY)) {
			Settings.toggleConfirmOverwrite();
			Theme.click(1.1F);
		} else if (this.toastsToggle.contains(mouseX, mouseY)) {
			Settings.toggleToasts();
			Theme.click(1.1F);
		} else if (this.notificationsToggle.contains(mouseX, mouseY)) {
			Settings.toggleNotifications();
			Theme.click(1.1F);
		} else if (this.changeFolderButton.contains(mouseX, mouseY)) {
			Theme.click();
			this.openDownloadPicker();
		} else if (this.openFolderButton.contains(mouseX, mouseY)) {
			Theme.click();
			this.openDownloadFolder();
		} else if (this.resetFolderButton.contains(mouseX, mouseY)) {
			Theme.click();
			Settings.clearDownloadDirectory();
		} else if (this.gridDensityButton.contains(mouseX, mouseY)) {
			Theme.click();
			Settings.cycleGridDensity();
			// Recompute the grid so the new column count takes effect immediately.
			this.columns = Math.max(2, Math.min(7, columnsFor(this.contentWidth) + Settings.gridDensity()));
			this.cardWidth = (this.contentWidth - GUTTER * (this.columns - 1)) / this.columns;
			this.cardHeight = imageHeight(this.cardWidth) + CAPTION_HEIGHT;
			this.refilter();
		} else if (this.clearCacheButton.contains(mouseX, mouseY)) {
			Theme.click();
			ImageStore.releaseAll();
			ImageStore.clearDiskCache();
			SchematicPreview.clearCache();
			Toasts.push("Cache cleared", "Thumbnails and previews will reload as needed.",
					new ItemStack(Items.BUCKET));
		} else if (this.termsButton.contains(mouseX, mouseY)) {
			Theme.click();
			this.openTerms();
		}

		return true;
	}

	/** Native folder chooser, on its own thread so the blocking dialog does not freeze the render loop. */
	private void openDownloadPicker() {
		new Thread(() -> {
			String result;

			try {
				result = TinyFileDialogs.tinyfd_selectFolderDialog(
						"Choose a download folder", Settings.downloadDirectory().toString());
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Folder picker failed", e);
				return;
			}

			if (result == null || result.isBlank()) {
				return;
			}

			Minecraft.getInstance().execute(() -> Settings.setDownloadDirectory(Path.of(result.trim())));
		}, "schematicindex-folder-picker").start();
	}

	private void openDownloadFolder() {
		try {
			Path directory = Settings.downloadDirectory();
			Files.createDirectories(directory);
			Util.getPlatform().openPath(directory);
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Could not open the download folder", e);
		}
	}

	private void setLayerFromMouse(double mouseX) {
		if (this.layerSlider.width <= 0 || this.detail == null) {
			return;
		}

		float fraction = (float) ((mouseX - this.layerSlider.x) / this.layerSlider.width);
		fraction = Math.max(0.0F, Math.min(1.0F, fraction));

		// Snap to whole layers of the rendered model, floored at 1 so the bottom slice always renders.
		int total = this.detailLayerHeight();
		int layers = Math.max(1, Math.min(total, Math.round(fraction * total)));
		this.detailLayer = (float) layers / total;
	}

	/** The Y height, in block layers, of the model currently in the 3D preview. */
	private int detailLayerHeight() {
		int height = this.detail == null ? -1 : SchematicPreview.layerHeight(this.detail.schematicSlot());
		return height > 0 ? height : Math.max(1, this.detail == null ? 1 : this.detail.sizeY());
	}

	private void startDownload(SchematicEntry entry) {
		// Ask before clobbering a file the player already has, when they have opted into the prompt. A
		// download that already finished for this post re-runs freely - the button reads "Saved".
		if (Settings.confirmOverwrite() && this.pendingOverwrite == null) {
			Download.Progress progress = Download.progress(entry.id());
			boolean alreadyDone = progress != null && progress.state() == Download.State.DONE;

			if (!alreadyDone && Files.exists(Download.resolveTarget(entry.title() + ".litematic"))) {
				this.pendingOverwrite = entry;
				Theme.click(0.9F);
				return;
			}
		}

		this.beginDownload(entry);
	}

	private void beginDownload(SchematicEntry entry) {
		Theme.click(1.2F);
		String url = entry.fileUrl();

		if (url != null && !url.isBlank()) {
			Download.start(entry.id(), entry.title() + ".litematic", url, null);
		} else {
			// A locally-picked upload preview with no server URL: copy the file directly.
			Path source = SchematicPreview.pathFor(entry.schematicSlot());

			if (source == null) {
				this.status = "No file to download.";
				return;
			}

			Download.start(entry.id(), entry.title() + ".litematic", null, source);
		}

		Backend.downloadAsync(entry.id());
		this.status = "Downloading into your schematics folder...";
	}

	private boolean focusField(EditBox box, MouseButtonEvent event, boolean doubleClick, double mouseX, double mouseY) {
		if (!Theme.inside(mouseX, mouseY, box.getX() - 6, box.getY() - 4, box.getWidth() + 12, FIELD_HEIGHT)) {
			return false;
		}

		this.setFocused(box);
		box.setFocused(true);
		box.mouseClicked(event, doubleClick);
		return true;
	}

	private void submitPost() {
		String title = this.titleBox.getValue().trim();

		if (title.isEmpty()) {
			this.formStatus = "Give it a name first.";
			return;
		}

		if (this.formSchematic == null) {
			this.formStatus = "Choose the .litematic file first.";
			return;
		}

		if (this.formPictures.isEmpty()) {
			this.formStatus = "Select at least one picture.";
			return;
		}

		if (UploaderAccess.code() == null) {
			this.formStatus = "Your session expired - unlock again.";
			return;
		}

		JsonObject meta = new JsonObject();
		meta.addProperty("title", title);
		meta.addProperty("thumbnailName", this.thumbnailBox.getValue().trim());
		meta.addProperty("designer", this.designerBox.getValue().trim());
		meta.addProperty("category", this.formCategory.name());
		meta.addProperty("description", this.descriptionBox.getValue().trim());

		String code = UploaderAccess.code();
		Path schematic = this.formSchematic;
		List<Path> pictures = new ArrayList<>(this.formPictures);
		this.formStatus = "Uploading...";

		Thread worker = new Thread(() -> {
			int status = Backend.upload(code, meta.toString(), schematic, pictures);
			Minecraft.getInstance().execute(() -> {
				if (status == 201) {
					this.formPictures.clear();
					this.formPictureStart = -1;
					this.formPicturePreview = 0;
					this.formSchematic = null;
					this.titleBox.setValue("");
					this.thumbnailBox.setValue("");
					this.designerBox.setValue("");
					this.descriptionBox.setValue("");
					this.formStatus = "";
					Catalogue.refresh();
					this.switchPage(Page.BROWSE);
				} else {
					this.formStatus = "Upload failed (" + status + "). Check the file and try again.";
				}
			});
		}, "schematicindex-upload");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Native file chooser, via the tinyfd library Minecraft already ships. It runs on its own thread
	 * because the dialog blocks until the user chooses, and the selection is applied back on the
	 * render thread.
	 */
	private void openPicturePicker() {
		new Thread(() -> {
			String result;

			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(3);
				filters.put(stack.UTF8("*.png"));
				filters.put(stack.UTF8("*.jpg"));
				filters.put(stack.UTF8("*.jpeg"));
				filters.flip();
				result = TinyFileDialogs.tinyfd_openFileDialog("Select 1 to 5 pictures", "", filters, "Images", true);
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("File picker failed", e);
				return;
			}

			if (result == null || result.isBlank()) {
				return;
			}

			List<Path> chosen = Arrays.stream(result.split("\\|"))
					.filter(value -> !value.isBlank())
					.map(Path::of)
					.toList();
			Minecraft.getInstance().execute(() -> this.applyPictures(chosen));
		}, "schematicindex-picture-picker").start();
	}

	private void openSchematicPicker() {
		new Thread(() -> {
			String result;

			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer filters = stack.mallocPointer(1);
				filters.put(stack.UTF8("*.litematic"));
				filters.flip();
				result = TinyFileDialogs.tinyfd_openFileDialog(
						"Choose the schematic", "", filters, "Litematica schematic", false);
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("File picker failed", e);
				return;
			}

			if (result == null || result.isBlank()) {
				return;
			}

			Path chosen = Path.of(result.trim());
			Minecraft.getInstance().execute(() -> {
				this.formSchematic = chosen;
				this.formStatus = chosen.getFileName() + " selected.";
			});
		}, "schematicindex-schematic-picker").start();
	}

	private void applyPictures(List<Path> chosen) {
		boolean truncated = chosen.size() > 5;
		List<Path> capped = truncated ? List.copyOf(chosen.subList(0, 5)) : chosen;

		this.formPictures.clear();
		this.formPictures.addAll(capped);
		this.formPictureStart = ImageStore.register(capped);
		this.formPicturePreview = 0;
		this.formStatus = truncated
				? "Only the first 5 pictures were kept."
				: capped.size() + (capped.size() == 1 ? " picture selected." : " pictures selected.");
	}

	private void clickDetail(double mouseX, double mouseY) {
		SchematicEntry entry = this.detail;

		if (entry == null) {
			return;
		}

		if (this.detailClose.contains(mouseX, mouseY) || this.detailCornerClose.contains(mouseX, mouseY)) {
			Theme.click(0.9F);
			this.detail = null;
			this.followConfirm = false;
			this.pendingOverwrite = null;
			this.reportOpen = false;
			this.reportContextOpen = false;
			this.status = "";
		} else if (this.detailHeart.contains(mouseX, mouseY)) {
			toggleLike(entry);
		} else if (this.detailPrev.contains(mouseX, mouseY)) {
			Theme.click();
			this.detailImage = Math.floorMod(this.detailImage - 1, entry.imageCount());
		} else if (this.detailNext.contains(mouseX, mouseY)) {
			Theme.click();
			this.detailImage = Math.floorMod(this.detailImage + 1, entry.imageCount());
		} else if (this.cutawayToggle.contains(mouseX, mouseY)) {
			this.cutaway = !this.cutaway;
			Theme.click(this.cutaway ? 1.3F : 0.9F);
		} else if (this.freeLookToggle.contains(mouseX, mouseY)) {
			this.freeLook = !this.freeLook;
			Theme.click(this.freeLook ? 1.3F : 0.9F);
			// Freeze the eye where the orbit camera was, so toggling does not teleport the view.
			this.freeEye = this.freeLook
					? SchematicPreview.eye(entry.schematicSlot(), this.detailYaw, this.detailPitch,
							this.detailZoom, this.cutaway)
					: null;
			this.status = this.freeLook ? "Drag to look around. Zoom is locked." : "";
		} else if (this.resetViewButton.contains(mouseX, mouseY)) {
			Theme.click();
			this.resetCamera();
		} else if (this.detailSave.contains(mouseX, mouseY)) {
			toggleSaved(entry);
		} else if (this.detailFollow.contains(mouseX, mouseY)) {
			if (!Follows.isFollowing(entry.poster())) {
				Follows.toggle(entry.poster());
				Theme.follow();
				Toasts.push("Followed " + entry.poster(), "You'll be notified whenever someone you follow, posts",
						new ItemStack(Items.PLAYER_HEAD));
				this.followConfirm = false;
			} else if (!this.followConfirm) {
				// First click while following asks to confirm instead of dropping it straight away.
				this.followConfirm = true;
				this.followConfirmAt = System.currentTimeMillis();
				Theme.click(0.9F);
			} else {
				Follows.toggle(entry.poster());
				Theme.click(0.8F);
				this.followConfirm = false;
			}
		} else if (this.detailDownload.contains(mouseX, mouseY)) {
			this.startDownload(entry);
		} else if (this.detailPreview3d.contains(mouseX, mouseY)) {
			Theme.click(1.2F);
			this.detailModel = !this.detailModel;
			this.status = this.detailModel ? "Drag to orbit, scroll to zoom." : "";
		} else if (this.detailReport.contains(mouseX, mouseY)) {
			Theme.click(0.9F);
			this.reportOpen = true;
		}
	}

	private void clickReportPicker(double mouseX, double mouseY) {
		for (int i = 0; i < this.reportReasonRects.length; i++) {
			Rect rect = this.reportReasonRects[i];

			if (rect != null && rect.contains(mouseX, mouseY)) {
				// Move on to the context step so they can explain the report.
				this.reportOpen = false;
				this.reportContextOpen = true;
				this.reportReasonIndex = i;
				this.reportContext = "";
				Theme.click(1.1F);
				return;
			}
		}

		if (this.reportCancel.contains(mouseX, mouseY)) {
			this.reportOpen = false;
			Theme.click(0.9F);
		}
	}

	private void clickReportContext(double mouseX, double mouseY) {
		if (this.reportSubmit.contains(mouseX, mouseY)) {
			this.submitReport();
		}
		// Clicks elsewhere are swallowed; Esc backs out.
	}

	private void submitReport() {
		SchematicEntry entry = this.detail;
		String code = this.reportReasonIndex >= 0 && this.reportReasonIndex < REPORT_CODES.length
				? REPORT_CODES[this.reportReasonIndex] : "OTHER";

		if (entry != null) {
			Backend.reportAsync(entry.id(), code, this.reportContext);
		}

		this.reportContextOpen = false;
		this.reportReasonIndex = -1;
		this.reportContext = "";
		Theme.click(1.1F);
		Toasts.push("Report submitted", "Thanks - we'll take a look.", new ItemStack(Items.PAPER));
	}

	private void clickOverwriteConfirm(double mouseX, double mouseY) {
		SchematicEntry entry = this.pendingOverwrite;

		if (this.overwriteReplace.contains(mouseX, mouseY)) {
			this.pendingOverwrite = null;

			if (entry != null) {
				this.beginDownload(entry);
			}
		} else if (this.overwriteCancel.contains(mouseX, mouseY)) {
			this.pendingOverwrite = null;
			Theme.click(0.9F);
			this.status = "";
		}
		// A click anywhere else on the scrim is swallowed: the card stays until it is answered.
	}

	private void resetCamera() {
		this.detailYaw = 35.0F;
		this.detailPitch = 28.0F;
		this.detailZoom = 1.0F;
		this.detailLayer = 1.0F;
		this.freeLook = false;
		this.freeEye = null;
		this.status = "";
	}

	private static void toggleLike(SchematicEntry entry) {
		boolean nowLiked = Bookmarks.toggleLike(entry.id());

		if (nowLiked) {
			LIKE_POPS.put(entry.id(), System.currentTimeMillis());
			Theme.like();
		} else {
			Theme.click(0.9F);
		}

		Backend.likeAsync(entry.id(), nowLiked);
	}

	private static long popAge(SchematicEntry entry) {
		Long popped = LIKE_POPS.get(entry.id());
		return popped == null ? -1L : System.currentTimeMillis() - popped;
	}

	private boolean heartAt(SchematicEntry entry, double mouseX, double mouseY) {
		int index = this.visible.indexOf(entry);

		if (index < 0) {
			return false;
		}

		int row = index / this.columns;
		int column = index % this.columns;
		int x = this.contentX + column * (this.cardWidth + GUTTER);
		int y = this.gridTop + row * (this.cardHeight + GUTTER) - Math.round(this.scroll);
		Rect heart = this.heartRect(x, y, imageHeight(this.cardWidth));
		return Theme.inside(mouseX, mouseY, heart.x - 2, heart.y - 2, HEART_SIZE + 4, HEART_SIZE + 4);
	}

	private @Nullable SchematicEntry entryAt(double mouseX, double mouseY) {
		if (mouseY < this.gridTop || mouseY >= this.gridBottom || mouseX < this.contentX) {
			return null;
		}

		int rowHeight = this.cardHeight + GUTTER;
		int relativeY = (int) (mouseY - this.gridTop + this.scroll);
		int row = relativeY / rowHeight;

		if (relativeY % rowHeight > this.cardHeight) {
			return null;
		}

		int relativeX = (int) (mouseX - this.contentX);
		int columnWidth = this.cardWidth + GUTTER;
		int column = relativeX / columnWidth;

		if (column >= this.columns || relativeX % columnWidth > this.cardWidth) {
			return null;
		}

		int index = row * this.columns + column;
		return index >= 0 && index < this.shownCap() ? this.visible.get(index) : null;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.draggingLayer && this.detail != null && this.detailModel) {
			this.setLayerFromMouse(event.x());
			return true;
		}

		// Orbiting continues while the button is held, even once the cursor leaves the preview -
		// otherwise a fast drag stops dead at the panel edge.
		if (this.orbiting && this.detail != null && this.detailModel) {
			// Free orbit: the model follows the mouse. Pitch stops short of the poles, where the
			// camera basis degenerates.
			this.detailYaw = (this.detailYaw + (float) dragX * 0.8F) % 360.0F;
			this.detailPitch = Math.max(-88.0F, Math.min(88.0F, this.detailPitch + (float) dragY * 0.8F));
			return true;
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.orbiting = false;
		this.draggingLayer = false;
		return super.mouseReleased(event);
	}

	/** Plays the press dip on whichever animated button a click landed on. */
	private void registerButtonPress(double mouseX, double mouseY) {
		Rect[] buttons;

		if (this.tosOpen) {
			buttons = this.tosScrolledBottom ? new Rect[]{this.tosAgree, this.tosDecline} : new Rect[0];
		} else if (this.tutorialActive) {
			buttons = new Rect[]{this.tutorialNext, this.tutorialBack, this.tutorialSkip};
		} else if (this.pendingOverwrite != null) {
			buttons = new Rect[]{this.overwriteReplace, this.overwriteCancel};
		} else if (this.reportOpen) {
			List<Rect> picker = new ArrayList<>();

			for (Rect rect : this.reportReasonRects) {
				if (rect != null) {
					picker.add(rect);
				}
			}

			picker.add(this.reportCancel);
			buttons = picker.toArray(new Rect[0]);
		} else if (this.reportContextOpen) {
			buttons = new Rect[]{this.reportSubmit};
		} else if (this.detail != null) {
			buttons = new Rect[]{this.detailClose, this.detailCornerClose, this.detailReport, this.detailSave,
					this.detailFollow, this.detailDownload, this.detailPreview3d, this.resetViewButton,
					this.cutawayToggle, this.freeLookToggle};
		} else {
			buttons = new Rect[]{this.closeButton, this.sortButton, this.retryButton, this.unlockButton,
					this.signOutButton, this.formCategoryButton, this.uploadPicturesButton,
					this.uploadSchematicButton, this.postButton, this.changeFolderButton,
					this.openFolderButton, this.resetFolderButton, this.soundsToggle, this.overwriteToggle,
					this.toastsToggle, this.notificationsToggle, this.gridDensityButton, this.clearCacheButton,
					this.termsButton};
		}

		for (Rect rect : buttons) {
			if (rect.contains(mouseX, mouseY)) {
				Theme.buttonPress(rect);
				return;
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.tosOpen) {
			this.tosScroll = Math.max(0.0F, Math.min(this.tosMaxScroll, this.tosScroll - (float) scrollY * SCROLL_STEP));
			return true;
		}

		if (this.tutorialActive) {
			return true;
		}

		if (this.detail != null) {
			// Look-around mode is a fixed viewpoint: turning is allowed, moving is not.
			if (this.detailModel && !this.freeLook && this.detailImageRect.contains(mouseX, mouseY)) {
				float factor = scrollY > 0 ? 1.18F : 1.0F / 1.18F;
				this.detailZoom = SchematicPreview.clampZoom(this.detail.schematicSlot(), this.detailZoom * factor);
			}

			return true;
		}

		// Upload and Settings are fixed-height forms; only the grid and news pages scroll.
		if (this.page == Page.UPLOAD || this.page == Page.SETTINGS) {
			return true;
		}

		this.scroll = Math.max(0.0F, Math.min(this.maxScroll, this.scroll - (float) scrollY * SCROLL_STEP));
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		// The context box captures typing directly, up to the character limit.
		if (this.reportContextOpen) {
			if (event.codepoint() >= ' ' && this.reportContext.length() < REPORT_CONTEXT_MAX) {
				this.reportContext += event.codepointAsString();
			}

			return true;
		}

		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// The terms gate swallows keys; Esc counts as declining, but only once it can be answered.
		if (this.tosOpen) {
			if (event.key() == 256 && this.tosScrolledBottom) {
				Settings.revokeTerms();
				this.onClose();
			}

			return true;
		}

		// During the tour, Esc skips it and other keys are swallowed.
		if (this.tutorialActive) {
			if (event.key() == 256) {
				this.finishTutorial();
			}

			return true;
		}

		// Context box: backspace edits, Enter submits, Esc backs out to the detail modal.
		if (this.reportContextOpen) {
			switch (event.key()) {
				case 259 -> {
					if (!this.reportContext.isEmpty()) {
						this.reportContext = this.reportContext.substring(0, this.reportContext.length() - 1);
					}
				}
				case 257, 335 -> this.submitReport();
				case 256 -> this.reportContextOpen = false;
				default -> {
				}
			}

			return true;
		}

		if (event.key() == 256 && this.detail != null) {
			// Esc backs out of a prompt first, leaving the detail modal open.
			if (this.pendingOverwrite != null) {
				this.pendingOverwrite = null;
				this.status = "";
				return true;
			}

			if (this.reportOpen) {
				this.reportOpen = false;
				return true;
			}

			this.detail = null;
			this.status = "";
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		ImageStore.releaseAll();
		SchematicPreview.releaseAll();

		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}

	/** Simple mutable hit rect - avoids allocating on every frame. */
	private static final class Rect {
		private int x;
		private int y;
		private int width;
		private int height;

		void set(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		boolean contains(double mouseX, double mouseY) {
			return this.width > 0 && Theme.inside(mouseX, mouseY, this.x, this.y, this.width, this.height);
		}
	}
}
