package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Errors;
import com.fudgedy.schematicindex.LoadedCache;
import com.fudgedy.schematicindex.Settings;
import com.fudgedy.schematicindex.UpdateGate;
import com.fudgedy.schematicindex.catalogue.Backend;
import com.fudgedy.schematicindex.catalogue.Bookmarks;
import com.fudgedy.schematicindex.catalogue.Catalogue;
import com.fudgedy.schematicindex.catalogue.Category;
import com.fudgedy.schematicindex.catalogue.CollectionStore;
import com.fudgedy.schematicindex.catalogue.Download;
import com.fudgedy.schematicindex.catalogue.Follows;
import com.fudgedy.schematicindex.catalogue.NewsFeed;
import com.fudgedy.schematicindex.catalogue.RemoteContent;
import com.fudgedy.schematicindex.catalogue.SchematicEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.FileType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
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

public class IndexScreen extends Screen {
	private static final int OUTER_MARGIN = 8;
	private static final int CONTENT_MAX_WIDTH = 720;
	private static final int TOP_BAR_HEIGHT = 32;
	private static final int RAIL_WIDTH = 34;
	private static final int RAIL_ITEM_HEIGHT = 34;
	private static final int PARTNER_RAIL_WIDTH = 58;
	
	private static final float RAIL_HOVER_GROW = 0.14F;
	private static final int RAIL_ITEM_GAP = 8;
	private static final int GUTTER = 6;
	private static final int CAPTION_HEIGHT = 28;
	private static final int CHIP_HEIGHT = 14;
	private static final int CHIP_GAP = 4;
	private static final int TRASH_CELL_WIDTH = 13; // trash glyph + padding reserved in named chips
	private static final int SCROLL_STEP = 24;
	private static final int HEART_SIZE = 9;
	private static final int FIELD_HEIGHT = 16;
	
	private static final int PAGE_SIZE = 12;
	
	private static final int LOADING_ROW = 20;

	private static final Map<String, Long> LIKE_POPS = new HashMap<>();

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

	// Remembered across menu close/reopen within a session (static, so a Minecraft
	// restart resets them). Lets reopening the menu land back on the same view.
	private static Catalogue.Sort sessionSort = Catalogue.Sort.TRENDING;
	private static Category sessionCategory = Category.ALL;
	private static float sessionScroll;
	private static int sessionShown = PAGE_SIZE;
	private Catalogue.Sort sort = Catalogue.Sort.TRENDING;
	private String query = "";
	private long catalogueRevision = -1;

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

	// Draggable scrollbar. Each scrollbar drawn this frame records its geometry and which
	// scroll channel it drives, so a click on the thumb can grab it and drag to scroll.
	private static final int SCROLLBAR_NONE = 0;
	private static final int SCROLLBAR_MAIN = 1; // this.scroll / this.maxScroll
	private static final int SCROLLBAR_DASH = 2; // dashScroll / dashMaxScroll
	private static final int SCROLLBAR_TOS = 3;  // tosScroll / tosMaxScroll
	private int scrollbarChannel = SCROLLBAR_NONE;
	private int scrollbarX, scrollbarWidth, scrollbarThumbY, scrollbarThumbHeight;
	private int scrollbarTrackTop, scrollbarTrackHeight;
	private boolean draggingScrollbar;
	private int dragScrollChannel = SCROLLBAR_NONE;
	private float scrollbarGrabOffset;
	
	private int shownCount = PAGE_SIZE;
	private int focusedCard = -1;

	// Which button is highlighted for arrow-key navigation in the active confirmation modal.
	// -1 means no button is highlighted yet (the ring only shows once an arrow key is pressed).
	private int modalFocus = -1;

	private EditBox searchBox;
	private EditBox codeBox;
	private EditBox titleBox;
	private EditBox thumbnailBox;
	private EditBox designerBox;
	private EditBox descriptionBox;
	private EditBox editTitleBox;
	private EditBox editThumbnailBox;
	private EditBox editDesignerBox;
	private EditBox editDescriptionBox;

	// Right-click options / edit / unpublish for the creator's own dashboard posts.
	private boolean postOptionsOpen;
	private @Nullable String postOptionsId;
	private @Nullable String postOptionsTitle;
	private final Rect postOptionsEdit = new Rect();
	private final Rect postOptionsUnpublish = new Rect();
	private final Rect postOptionsCancel = new Rect();
	private final Rect postOptionsBounds = new Rect();

	private boolean editPostOpen;
	private @Nullable String editPostId;
	private Category editCategory = Category.FARMS;
	private String editStatus = "";
	private final Rect editCategoryButton = new Rect();
	private final Rect editSave = new Rect();
	private final Rect editCancel = new Rect();
	private final Rect editBounds = new Rect();

	private boolean unpublishOpen;
	private @Nullable String unpublishId;
	private @Nullable String unpublishTitle;
	private final Rect unpublishConfirm = new Rect();
	private final Rect unpublishCancel = new Rect();
	private final Rect unpublishBounds = new Rect();

	private final Rect closeButton = new Rect();
	private final Rect backToTopButton = new Rect();
	private final Rect sortButton = new Rect();
	private final Rect downloadFilterButton = new Rect();
	// 0 = all, 1 = only downloaded, 2 = not yet downloaded.
	private int downloadFilter;
	private final java.util.Set<String> downloadedNames = new java.util.HashSet<>();
	private final Rect retryButton = new Rect();
	private final List<Rect> chipRects = new ArrayList<>();
	private final List<Category> chipOrder = new ArrayList<>();
	private final List<Rect> railRects = new ArrayList<>();

	private final Rect unlockButton = new Rect();
	private final Rect signOutButton = new Rect();
	private final Rect formCategoryButton = new Rect();
	private final Rect formImagePrev = new Rect();
	private final Rect formImageNext = new Rect();
	private final Rect formImageRemove = new Rect();
	private final Rect formThumbnailButton = new Rect();
	private int formThumbnailIndex;
	private final Rect uploadPicturesButton = new Rect();
	private final Rect uploadSchematicButton = new Rect();
	private @Nullable Path formSchematic;
	private final List<Path> formPictures = new ArrayList<>();
	private int formPictureStart = -1;
	private int formPicturePreview;
	private final Rect postButton = new Rect();
	private Category formCategory = Category.FARMS;
	private String formStatus = "";
	private boolean uploading;
	private long uploadStartedAt;
	private long uploadFileSize;
	private int formSizeX;
	private int formSizeY;
	private int formSizeZ;
	private int formBlockCount;
	private boolean designerWarnOpen;
	private boolean designerWarnDontShow;
	private final Rect designerWarnCancel = new Rect();
	private final Rect designerWarnUpload = new Rect();
	private final Rect designerWarnToggle = new Rect();
	private boolean uploadFormOpen;

	// Post ids in the order they were last opened, most recent first (in-memory; cleared on
	// Minecraft restart). Drives the recently-viewed top row on the default browse view.
	private static final List<String> RECENT_VIEWED = new ArrayList<>();
	private static final int RECENT_VIEWED_MAX = 24;

	// Posts uploaded after this time are flagged "New". Captured once per Minecraft launch from
	// the stored last-visit time, which is then advanced to now.
	private static long newSinceCutoff = -1L;

	// In-memory upload draft, kept across closing/reopening the menu so a half-filled post
	// survives an accidental tab switch or menu close. Cleared on Minecraft restart or a
	// successful upload (static, not persisted to disk).
	private static boolean draftSaved;
	private static boolean draftFormOpen;
	// Passive "update available" notice for the notify-only path (lock opted out): show it at
	// most once per launch when the mod screen opens, never on the title screen.
	private static boolean updateNoticeShown;
	private static String draftTitle = "";
	private static String draftThumbnail = "";
	private static String draftDesigner = "";
	private static String draftDescription = "";
	private static @Nullable Category draftCategory;
	private static @Nullable Path draftSchematic;
	private static final List<Path> draftPictures = new ArrayList<>();

	private boolean myStatsLoading;
	private boolean myStatsLoaded;
	private int myPostsCount;
	private int myViews;
	private int myDownloads;
	private int myLikes;
	private int myFollowers;
	private double myRating;
	private int myRatingCount;
	private String[] myDays = new String[0];
	private int[] myViewSeries = new int[0];
	private int[] myDownloadSeries = new int[0];
	private int[] myLikeSeries = new int[0];
	private int[] myStarSeries = new int[0];
	private final List<SchematicEntry> myPosts = new ArrayList<>();
	// Per-post daily series keyed by post id (views/downloads/likes), for the selected-post graph.
	private final java.util.Map<String, int[][]> myPostSeries = new java.util.HashMap<>();
	// When set, the graph shows just this post's history instead of the totals.
	private @Nullable String selectedDashPost;
	private final List<Rect> myPostCardRects = new ArrayList<>();
	private final List<String> myPostCardIds = new ArrayList<>();
	private final List<Rect> myPostEditRects = new ArrayList<>();
	private final Rect dashUploadButton = new Rect();
	private final Rect uploadBackButton = new Rect();
	private float dashScroll;
	private float dashMaxScroll;
	private int dashViewportTop;
	private int dashViewportBottom;
	private Category myFilter = Category.ALL;
	private final List<Rect> myFilterChips = new ArrayList<>();
	private boolean myStatsOk;
	private int graphMode;
	private long graphAnimStart;
	private final Rect legendViewsRect = new Rect();
	private final Rect legendDownloadsRect = new Rect();
	private final Rect legendLikesRect = new Rect();
	private final Rect legendStarsRect = new Rect();

	private @Nullable SchematicEntry detail;
	private long detailOpenedAt;
	private int detailImage;
	private boolean detailModel;
	private boolean detailDownloadLocked;
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
	private final Rect detailLoad = new Rect();
	private final Rect detailPreview3d = new Rect();
	private final Rect detailSave = new Rect();
	private final Rect detailFollow = new Rect();
	private final Rect detailClose = new Rect();
	private final Rect detailCornerClose = new Rect();
	private final Rect detailHeart = new Rect();

	// Star rating state for the open post (kept mutable so it updates after the user rates).
	private int detailMyStars;      // this device's rating in half-stars (0..10)
	private double detailStarAvg;   // average rating 0..5
	private int detailStarCount;    // number of ratings
	private final Rect[] detailStarRects = {new Rect(), new Rect(), new Rect(), new Rect(), new Rect()};

	// Debounced rating: clicks update the stars instantly and locally, but the value is only sent
	// once the user stops clicking (avoids spamming the server and the flash from racing responses).
	private int pendingRateValue = -1;
	private long pendingRateAt;
	private @Nullable String pendingRateId;
	private static final long RATE_DEBOUNCE_MS = 550L;
	
	private @Nullable SchematicEntry pendingOverwrite;
	private final Rect overwriteReplace = new Rect();
	private final Rect overwriteCancel = new Rect();
	
	private static final String[] REPORT_REASONS = {
			"NSFW / Explicit Content", "Stealing Credit", "Spam / Misleading", "Other"};
	
	private static final String[] REPORT_CODES = {"NSFW", "STOLEN", "SPAM", "OTHER"};
	private final Rect detailReport = new Rect();
	private boolean reportOpen;
	private final Rect[] reportReasonRects = new Rect[REPORT_REASONS.length];
	private final Rect reportCancel = new Rect();
	
	private boolean reportContextOpen;
	private int reportReasonIndex = -1;
	private String reportContext = "";
	private final Rect reportSubmit = new Rect();
	private static final int REPORT_CONTEXT_MAX = 99;
	
	private float detailLayer = 1.0F;
	private boolean draggingLayer;
	
	private boolean followConfirm;
	private long followConfirmAt;
	private String status = "";

	private final Rect soundsToggle = new Rect();
	private final Rect volumeSliderTrack = new Rect();
	private boolean draggingVolume;
	private final Rect userNotificationsToggle = new Rect();
	private final Rect creatorNotificationsToggle = new Rect();
	private final Rect overwriteToggle = new Rect();
	private final Rect changeFolderButton = new Rect();
	private final Rect openFolderButton = new Rect();
	private final Rect resetFolderButton = new Rect();
	private final Rect gridDensityButton = new Rect();
	private final Rect clearCacheButton = new Rect();
	private final Rect toastsToggle = new Rect();
	private final Rect notificationsToggle = new Rect();
	private final Rect updateLockToggle = new Rect();
	private final Rect resetTokenButton = new Rect();
	private final Rect termsButton = new Rect();

	private @Nullable RemoteContent.Announcement bannerAnnouncement;
	private @Nullable String bannerId;
	private long bannerAnimStart;
	private boolean bannerEntering;
	private boolean bannerActive;
	private final Rect bannerClose = new Rect();
	private long lastContentPoll;
	private long lastNotifPoll;
	private int lastPartnerCount = -1;
	private final List<Rect> railLinkRects = new ArrayList<>();
	private final List<Rect> partnerRects = new ArrayList<>();

	private boolean errorOpen;
	private String errorCode = "";
	private final Rect errorClose = new Rect();
	private final Rect errorReport = new Rect();

	// Card bounds captured during render so a click on the scrim outside them dismisses the modal.
	private final Rect errorBounds = new Rect();
	private final Rect nameBounds = new Rect();
	private final Rect deleteBounds = new Rect();
	private final Rect designerWarnBounds = new Rect();
	private final Rect detailBounds = new Rect();
	private final Rect overwriteBounds = new Rect();
	private final Rect reportBounds = new Rect();

	// Collections (local, user-made groups of posts shown in the Saved tab).
	private @Nullable String activeCollection;
	private @Nullable String addingToCollection;
	private final List<Rect> collectionChips = new ArrayList<>();
	private final Rect newCollectionChip = new Rect();
	private final Rect addPostsChip = new Rect();
	private final Rect loadCodeChip = new Rect();
	private final Rect generateCodeChip = new Rect();
	private @Nullable String sharedCode;   // generated share code for the active collection
	private boolean sharingCode;
	private long codeCopiedAt;             // shows "Copied!" on the chip briefly after copying

	// Modal shown when an upload is rejected as a duplicate schematic.
	private boolean duplicateOpen;
	private final Rect duplicateClose = new Rect();
	private final Rect duplicateBounds = new Rect();

	// Modal for entering a shared collection code.
	private boolean loadCodeOpen;
	private String loadCodeInput = "";
	private String loadCodeStatus = "";
	private final Rect loadCodeConfirm = new Rect();
	private final Rect loadCodeCancel = new Rect();
	private final Rect loadCodeBounds = new Rect();

	private final Rect addModeDone = new Rect();
	private final Rect detailCollection = new Rect();
	private boolean collectionMenuOpen;
	private final List<Rect> collectionMenuRects = new ArrayList<>();
	private final Rect collectionMenuNew = new Rect();
	private boolean nameInputOpen;
	private String nameInput = "";
	private final Rect nameConfirm = new Rect();
	private final Rect nameCancel = new Rect();
	private @Nullable String namePendingPost;

	private boolean deleteCollectionOpen;
	private @Nullable String deleteCollectionName;
	private final Rect deleteCollectionConfirm = new Rect();
	private final Rect deleteCollectionCancel = new Rect();

	// Right-click options for a collection: rename / cancel / delete.
	private boolean collectionOptionsOpen;
	private @Nullable String collectionOptionsName;
	private final Rect collectionOptionsRename = new Rect();
	private final Rect collectionOptionsDelete = new Rect();
	private final Rect collectionOptionsCancel = new Rect();
	private final Rect collectionOptionsBounds = new Rect();

	// Rename modal with a text box pre-filled with the current name.
	private boolean renameCollectionOpen;
	private @Nullable String renameCollectionFrom;
	private String renameInput = "";
	private final Rect renameConfirm = new Rect();
	private final Rect renameCancel = new Rect();
	private final Rect renameBounds = new Rect();

	// Creator profile: a grid filtered to one poster, with an Instagram-style header.
	private @Nullable String profilePoster;
	private @Nullable String profileIgn;
	private int profileFollowers = -1;
	private int profilePosts = -1;
	private int profileDownloads = -1;
	private final Rect profileBack = new Rect();
	private final Rect profileFollow = new Rect();
	private final Rect detailPosterRect = new Rect();
	private final Rect detailViewProfile = new Rect();

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

	private static final String TERMS_BODY =
			"By using The Schematic Index, you agree to these terms. If you do not agree, you cannot use "
					+ "the online service.\n\n"
					+ "Data & Privacy: Nothing is sent anywhere until you accept these terms. Once accepted, "
					+ "the Service connects to external servers to sync the online catalog. "
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

		this.sort = sessionSort;
		this.category = sessionCategory;

		this.layoutContent();

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
		this.restoreDraft();
		this.refreshDownloadedNames();

		// Once per launch: remember what counts as "new since last visit", then mark this visit.
		if (newSinceCutoff < 0L) {
			newSinceCutoff = Settings.lastVisitAt();
			Settings.setLastVisitAt(System.currentTimeMillis());
		}

		int avgBoldChar = Math.max(4, this.font.width(Theme.bold("abcdefghijklmnopqrstuvwxyz")) / 26 + 1);
		this.thumbnailBox.setMaxLength(Math.max(10, (this.cardWidth - 12) / avgBoldChar));
		this.titleBox.setMaxLength(Math.max(24, 190 / avgBoldChar));

		this.layoutChips();

		if (!Settings.termsAccepted()) {
			this.openTerms();
		} else {
			this.maybeStartTutorial();
			this.maybeNotifyUpdate();
		}

		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.gridBottom = this.height - OUTER_MARGIN;
		this.refilter();

		this.shownCount = Math.max(PAGE_SIZE, Math.min(sessionShown, this.visible.size()));
		this.recomputeScrollBounds();
		this.scroll = Math.min(sessionScroll, this.maxScroll);
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

		this.editTitleBox = this.textField(0, 0, 100, "Schematic name",
				this.editTitleBox == null ? "" : this.editTitleBox.getValue());
		this.editThumbnailBox = this.textField(0, 0, 100, "Thumbnail name",
				this.editThumbnailBox == null ? "" : this.editThumbnailBox.getValue());
		this.editDesignerBox = this.textField(0, 0, 100, "Designed by",
				this.editDesignerBox == null ? "" : this.editDesignerBox.getValue());
		this.editDescriptionBox = this.textField(0, 0, 100, "Description",
				this.editDescriptionBox == null ? "" : this.editDescriptionBox.getValue());
	}

	// Restores an in-memory upload draft into the freshly built form (once per screen).
	private void restoreDraft() {
		if (!draftSaved) {
			return;
		}

		this.fillEditField(this.titleBox, draftTitle);
		this.fillEditField(this.thumbnailBox, draftThumbnail);
		this.fillEditField(this.designerBox, draftDesigner);
		this.fillEditField(this.descriptionBox, draftDescription);

		if (draftCategory != null) {
			this.formCategory = draftCategory;
		}

		this.formSchematic = draftSchematic;
		this.formPictures.clear();
		this.formPictures.addAll(draftPictures);
		this.uploadFormOpen = draftFormOpen;
	}

	// Snapshots the current form so it survives closing and reopening the menu.
	private void saveDraft() {
		if (!UploaderAccess.unlocked() || this.titleBox == null) {
			return;
		}

		draftTitle = this.titleBox.getValue();
		draftThumbnail = this.thumbnailBox.getValue();
		draftDesigner = this.designerBox.getValue();
		draftDescription = this.descriptionBox.getValue();
		draftCategory = this.formCategory;
		draftSchematic = this.formSchematic;
		draftPictures.clear();
		draftPictures.addAll(this.formPictures);
		draftFormOpen = this.uploadFormOpen;
		draftSaved = true;
	}

	private static void clearDraft() {
		draftSaved = false;
		draftFormOpen = false;
		draftTitle = "";
		draftThumbnail = "";
		draftDesigner = "";
		draftDescription = "";
		draftCategory = null;
		draftSchematic = null;
		draftPictures.clear();
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

		if (this.profilePoster != null) {
			this.chipRowHeight = 128;
			this.sortButton.set(0, 0, 0, 0);
			return;
		}

		if (this.page != Page.BROWSE && this.page != Page.SAVED) {
			this.chipRowHeight = 26;
			return;
		}

		if (this.page == Page.SAVED) {
			this.layoutCollectionChips();
			return;
		}

		String sortLabel = "Sort: " + this.sort.label();
		int sortWidth = this.font.width(Theme.bold(sortLabel)) + 12;
		int filterWidth = this.font.width(Theme.bold(this.downloadFilterLabel())) + 12;
		int firstRowLimit = this.contentX + this.contentWidth - sortWidth - 6 - filterWidth - 8;
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
		this.downloadFilterButton.set(this.sortButton.x - 6 - filterWidth, TOP_BAR_HEIGHT + 6, filterWidth, CHIP_HEIGHT);
	}

	private void layoutCollectionChips() {
		this.collectionChips.clear();

		String sortLabel = "Sort: " + this.sort.label();
		int sortWidth = this.font.width(Theme.bold(sortLabel)) + 12;
		int firstRowLimit = this.contentX + this.contentWidth - sortWidth - 8;
		int limit = this.contentX + this.contentWidth;
		int x = this.contentX;
		int[] row = {0};

		List<String> labels = new ArrayList<>();
		labels.add("All saved");
		labels.addAll(CollectionStore.names());

		for (int idx = 0; idx < labels.size(); idx++) {
			int width = this.font.width(Theme.bold(labels.get(idx))) + 12;

			// Named collections carry a trash icon on the right, so reserve room for it.
			if (idx > 0) {
				width += TRASH_CELL_WIDTH;
			}

			x = this.wrapChip(x, width, firstRowLimit, limit, row);
			Rect rect = new Rect();
			rect.set(x, TOP_BAR_HEIGHT + 6 + row[0] * (CHIP_HEIGHT + CHIP_GAP), width, CHIP_HEIGHT);
			this.collectionChips.add(rect);
			x += width + CHIP_GAP;
		}

		int newWidth = this.font.width(Theme.bold("+ New")) + 12;
		x = this.wrapChip(x, newWidth, firstRowLimit, limit, row);
		this.newCollectionChip.set(x, TOP_BAR_HEIGHT + 6 + row[0] * (CHIP_HEIGHT + CHIP_GAP), newWidth, CHIP_HEIGHT);
		x += newWidth + CHIP_GAP;

		if (this.activeCollection != null) {
			int addWidth = this.font.width(Theme.bold("+ Add posts")) + 12;
			x = this.wrapChip(x, addWidth, firstRowLimit, limit, row);
			this.addPostsChip.set(x, TOP_BAR_HEIGHT + 6 + row[0] * (CHIP_HEIGHT + CHIP_GAP), addWidth, CHIP_HEIGHT);
			x += addWidth + CHIP_GAP;

			String genLabel = this.generateCodeLabel();
			int genWidth = this.font.width(Theme.bold(genLabel)) + 12;
			x = this.wrapChip(x, genWidth, firstRowLimit, limit, row);
			this.generateCodeChip.set(x, TOP_BAR_HEIGHT + 6 + row[0] * (CHIP_HEIGHT + CHIP_GAP), genWidth, CHIP_HEIGHT);
			x += genWidth + CHIP_GAP;
		} else {
			this.addPostsChip.set(0, 0, 0, 0);
			this.generateCodeChip.set(0, 0, 0, 0);
		}

		int loadWidth = this.font.width(Theme.bold("Load code")) + 12;
		x = this.wrapChip(x, loadWidth, firstRowLimit, limit, row);
		this.loadCodeChip.set(x, TOP_BAR_HEIGHT + 6 + row[0] * (CHIP_HEIGHT + CHIP_GAP), loadWidth, CHIP_HEIGHT);
		x += loadWidth + CHIP_GAP;

		this.chipRowHeight = 6 + (row[0] + 1) * CHIP_HEIGHT + row[0] * CHIP_GAP + 6;
		this.sortButton.set(this.contentX + this.contentWidth - sortWidth, TOP_BAR_HEIGHT + 6, sortWidth, CHIP_HEIGHT);
	}

	private String generateCodeLabel() {
		if (this.sharedCode != null) {
			return System.currentTimeMillis() - this.codeCopiedAt < 1400L ? "Copied!" : this.sharedCode;
		}

		return this.sharingCode ? "..." : "Generate code";
	}

	private int wrapChip(int x, int width, int firstRowLimit, int limit, int[] row) {
		int rowLimit = row[0] == 0 ? firstRowLimit : limit;

		if (x + width > rowLimit && x > this.contentX) {
			row[0]++;
			return this.contentX;
		}

		return x;
	}

	private void refilter() {
		this.visible.clear();
		String needle = this.query.trim().toLowerCase(Locale.ROOT);

		for (SchematicEntry entry : Catalogue.posts()) {
			if (this.profilePoster != null) {
				if (!entry.poster().equals(this.profilePoster)) {
					continue;
				}
			} else if (this.page == Page.SAVED) {
				if (this.activeCollection != null) {
					if (!CollectionStore.contains(this.activeCollection, entry.id())) {
						continue;
					}
				} else if (!isSaved(entry)) {
					continue;
				}
			}

			if (this.page != Page.SAVED && this.category != Category.ALL && entry.category() != this.category) {
				continue;
			}

			if (!needle.isEmpty()
					&& !entry.title().toLowerCase(Locale.ROOT).contains(needle)
					&& !entry.poster().toLowerCase(Locale.ROOT).contains(needle)
					&& !entry.designer().toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}

			// Download-status filter (browse only): 1 = only downloaded, 2 = not yet downloaded.
			if (this.downloadFilter != 0 && this.page == Page.BROWSE) {
				boolean owned = this.isDownloaded(entry);

				if (this.downloadFilter == 1 && !owned) {
					continue;
				}

				if (this.downloadFilter == 2 && owned) {
					continue;
				}
			}

			this.visible.add(entry);
		}

		Comparator<SchematicEntry> comparator;

		// On the Saved page, "Newest" means most-recently added to Saved (or to the active
		// collection), not the post's original upload date.
		if (this.page == Page.SAVED && this.sort == Catalogue.Sort.NEWEST) {
			java.util.List<String> order = this.activeCollection != null
					? new java.util.ArrayList<>(CollectionStore.postIds(this.activeCollection))
					: Bookmarks.savedOrder();
			java.util.Map<String, Integer> rank = new java.util.HashMap<>();

			for (int i = 0; i < order.size(); i++) {
				rank.put(order.get(i), i);
			}

			comparator = Comparator.comparingInt((SchematicEntry e) -> rank.getOrDefault(e.id(), -1)).reversed();
		} else {
			comparator = switch (this.sort) {
				case TRENDING -> Comparator.comparingDouble(SchematicEntry::trendScore).reversed()
						.thenComparing(Comparator.comparingLong(SchematicEntry::postedAt).reversed());
				case NEWEST -> Comparator.comparingLong(SchematicEntry::postedAt).reversed();
				case DOWNLOADS -> Comparator.comparingInt(SchematicEntry::downloads).reversed();
				case LIKES -> Comparator.comparingInt(IndexScreen::likesOf).reversed();
			};
		}

		this.visible.sort(comparator);

		// On the default browse view, pull the most recently viewed posts to the front so the
		// top row is your recent views (one row = however many columns the grid density shows).
		if (this.page == Page.BROWSE && this.profilePoster == null && this.category == Category.ALL
				&& needle.isEmpty() && this.downloadFilter == 0 && !RECENT_VIEWED.isEmpty()) {
			int topRow = Math.max(1, this.columns);
			List<SchematicEntry> front = new ArrayList<>();

			for (String id : RECENT_VIEWED) {
				if (front.size() >= topRow) {
					break;
				}

				for (int i = 0; i < this.visible.size(); i++) {
					if (this.visible.get(i).id().equals(id)) {
						front.add(this.visible.remove(i));
						break;
					}
				}
			}

			this.visible.addAll(0, front);
		}

		this.shownCount = PAGE_SIZE;
		this.focusedCard = -1;
		this.recomputeScrollBounds();
	}

	private void scrollToFocused() {
		if (this.focusedCard < 0) {
			return;
		}

		int rowHeight = this.cardHeight + GUTTER;
		int cardTop = (this.focusedCard / this.columns) * rowHeight;
		int cardBottom = cardTop + this.cardHeight;
		int viewTop = Math.round(this.scroll);
		int viewHeight = this.gridBottom - this.gridTop;

		if (cardTop < viewTop) {
			this.scroll = cardTop;
		} else if (cardBottom > viewTop + viewHeight) {
			this.scroll = cardBottom - viewHeight;
		}

		this.scroll = Math.max(0.0F, Math.min(this.scroll, this.maxScroll));
	}

	private boolean handleGridKey(int key) {
		int shown = this.shownCap();

		if (shown <= 0) {
			return false;
		}

		if (key == 257 || key == 335) {
			if (this.focusedCard >= 0 && this.focusedCard < this.visible.size()) {
				this.openDetail(this.visible.get(this.focusedCard));
			} else {
				this.focusedCard = 0;
				this.scrollToFocused();
			}

			return true;
		}

		if (key != 262 && key != 263 && key != 264 && key != 265) {
			return false;
		}

		if (this.focusedCard < 0) {
			this.focusedCard = 0;
		} else if (key == 262) {
			this.focusedCard = Math.min(shown - 1, this.focusedCard + 1);
		} else if (key == 263) {
			this.focusedCard = Math.max(0, this.focusedCard - 1);
		} else if (key == 264) {
			this.focusedCard = Math.min(shown - 1, this.focusedCard + this.columns);
		} else {
			this.focusedCard = Math.max(0, this.focusedCard - this.columns);
		}

		this.maybeLoadMore();
		this.scrollToFocused();
		return true;
	}

	private int shownCap() {
		return Math.min(this.shownCount, this.visible.size());
	}

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

	private static boolean isLikedBy(SchematicEntry entry) {
		return entry.liked() || Bookmarks.isLiked(entry.id());
	}

	private static int imageSlot(SchematicEntry entry, int offset) {
		return entry.imageStart() + offset;
	}

	private @Nullable Identifier imageTexture(SchematicEntry entry, int index) {
		List<String> urls = entry.imageUrls();

		if (!urls.isEmpty()) {
			return ImageStore.texture(urls.get(Math.floorMod(index, urls.size())));
		}

		return ImageStore.texture(imageSlot(entry, index));
	}

	private @Nullable Identifier thumbnailTexture(SchematicEntry entry) {
		if (entry.thumbnailUrl() != null && !entry.thumbnailUrl().isBlank()) {
			return ImageStore.thumbnail(entry.thumbnailUrl());
		}

		List<String> urls = entry.imageUrls();

		if (!urls.isEmpty()) {
			return ImageStore.thumbnail(urls.get(0));
		}

		return ImageStore.thumbnail(imageSlot(entry, 0));
	}

	@Override
	public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ctx.fill(0, 0, this.width, this.height, Theme.BACKDROP);
	}

	@Override
	public void render(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ImageStore.uploadPending();
		SchematicPreview.uploadPending();

		// Cleared each frame; whichever scrollbar draws will re-record its geometry below.
		this.scrollbarChannel = SCROLLBAR_NONE;

		if (Catalogue.revision() != this.catalogueRevision) {
			this.catalogueRevision = Catalogue.revision();
			this.refilter();
		}

		if (RemoteContent.partners().size() != this.lastPartnerCount) {
			this.lastPartnerCount = RemoteContent.partners().size();
			this.relayout();
		}

		if (Errors.hasPending() && !this.errorOpen) {
			this.errorCode = Errors.takePending();
			this.errorOpen = true;
		}

		this.renderBackground(ctx, mouseX, mouseY, partialTick);

		boolean modalOpen = this.detail != null;
		boolean overlayOpen = modalOpen || this.designerWarnOpen;
		int hoverX = overlayOpen ? -1 : mouseX;
		int hoverY = overlayOpen ? -1 : mouseY;

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
			this.renderAddModeBanner(ctx, hoverX, hoverY);
			this.renderPartnerRail(ctx, hoverX, hoverY);
		}

		this.renderRail(ctx, hoverX, hoverY);
		this.renderTopBar(ctx, hoverX, hoverY);
		this.renderBackToTop(ctx, hoverX, hoverY, overlayOpen);

		long nowMs = System.currentTimeMillis();

		if (nowMs - this.lastContentPoll > 10000L) {
			this.lastContentPoll = nowMs;
			RemoteContent.pollAsync();
		}

		if (UploaderAccess.unlocked() && Settings.creatorAlerts() && nowMs - this.lastNotifPoll > 30000L) {
			this.lastNotifPoll = nowMs;
			this.pollNotifications();
		}

		this.flushPendingRate(false);

		this.updateBanner();
		this.renderBanner(ctx, mouseX, mouseY);

		this.searchBox.setVisible(this.gridPage());

		if (this.gridPage()) {
			this.searchBox.render(ctx, mouseX, mouseY, partialTick);

			if (!overlayOpen) {
				this.renderSearchSuggestions(ctx);
			}
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

		if (this.designerWarnOpen) {
			this.renderDesignerWarn(ctx, mouseX, mouseY);
		}

		Toasts.render(ctx);

		if (this.tutorialActive) {
			this.renderTutorial(ctx, mouseX, mouseY);
		}

		if (this.tosOpen) {
			this.renderTos(ctx, mouseX, mouseY);
		}

		if (this.errorOpen) {
			this.renderError(ctx, mouseX, mouseY);
		}

		if (this.nameInputOpen) {
			this.renderNameInput(ctx, mouseX, mouseY);
		}

		if (this.loadCodeOpen) {
			this.renderLoadCode(ctx, mouseX, mouseY);
		}

		if (this.duplicateOpen) {
			this.renderDuplicate(ctx, mouseX, mouseY);
		}

		if (this.collectionOptionsOpen) {
			this.renderCollectionOptions(ctx, mouseX, mouseY);
		}

		if (this.renameCollectionOpen) {
			this.renderRenameCollection(ctx, mouseX, mouseY);
		}

		if (this.deleteCollectionOpen) {
			this.renderDeleteCollection(ctx, mouseX, mouseY);
		}

		if (this.postOptionsOpen) {
			this.renderPostOptions(ctx, mouseX, mouseY);
		}

		if (this.editPostOpen) {
			this.renderEditPost(ctx, mouseX, mouseY, partialTick);
		}

		if (this.unpublishOpen) {
			this.renderUnpublish(ctx, mouseX, mouseY);
		}

		// Arrow-key focus ring on the active confirmation/input modal's buttons.
		Rect[] focusButtons = this.modalFocusButtons();

		if (focusButtons != null && this.modalFocus >= 0 && this.modalFocus < focusButtons.length) {
			Rect f = focusButtons[this.modalFocus];

			if (f.width > 0 && f.height > 0) {
				Theme.roundedOutline(ctx, f.x - 1, f.y - 1, f.width + 2, f.height + 2, Theme.RADIUS_PILL,
						Theme.ACCENT_BRIGHT);
			}
		}
	}

	// Ordered focusable buttons of whichever confirmation/input modal is open (primary first),
	// or null if no such modal is active. Enables arrow-key navigation and Enter-to-activate.
	private Rect[] modalFocusButtons() {
		if (this.errorOpen) {
			String discord = RemoteContent.discord();
			return discord != null && !discord.isBlank()
					? new Rect[]{this.errorReport, this.errorClose}
					: new Rect[]{this.errorClose};
		}

		if (this.renameCollectionOpen) {
			return new Rect[]{this.renameConfirm, this.renameCancel};
		}

		if (this.collectionOptionsOpen) {
			return new Rect[]{this.collectionOptionsRename, this.collectionOptionsCancel, this.collectionOptionsDelete};
		}

		if (this.deleteCollectionOpen) {
			return new Rect[]{this.deleteCollectionConfirm, this.deleteCollectionCancel};
		}

		if (this.nameInputOpen) {
			return new Rect[]{this.nameConfirm, this.nameCancel};
		}

		if (this.pendingOverwrite != null) {
			return new Rect[]{this.overwriteReplace, this.overwriteCancel};
		}

		return null;
	}

	// Performs the action of the currently focused modal button (Enter key).
	private void activateModalFocus() {
		Rect[] buttons = this.modalFocusButtons();

		if (buttons == null) {
			return;
		}

		int i = this.modalFocus < 0 || this.modalFocus >= buttons.length ? 0 : this.modalFocus;

		if (this.errorOpen) {
			// Focus list may or may not include the report link, so dispatch by identity.
			if (buttons[i] == this.errorReport) {
				this.openLink(RemoteContent.discord());
			} else {
				this.errorOpen = false;
			}
		} else if (this.renameCollectionOpen) {
			if (i == 0) {
				this.confirmRename();
			} else {
				this.renameCollectionOpen = false;
				this.renameCollectionFrom = null;
			}
		} else if (this.collectionOptionsOpen) {
			String name = this.collectionOptionsName;

			if (i == 0 && name != null) {
				this.collectionOptionsOpen = false;
				this.openRenameCollection(name);
			} else if (i == 2) {
				this.deleteCollectionName = name;
				this.collectionOptionsOpen = false;
				this.deleteCollectionOpen = true;
				this.modalFocus = -1;
			} else {
				this.collectionOptionsOpen = false;
				this.collectionOptionsName = null;
			}
		} else if (this.deleteCollectionOpen) {
			if (i == 0) {
				if (this.deleteCollectionName != null) {
					CollectionStore.delete(this.deleteCollectionName);

					if (this.deleteCollectionName.equals(this.activeCollection)) {
						this.activeCollection = null;
					}
				}

				this.deleteCollectionOpen = false;
				this.deleteCollectionName = null;
				this.layoutChips();
				this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
				this.refilter();
			} else {
				this.deleteCollectionOpen = false;
				this.deleteCollectionName = null;
			}
		} else if (this.nameInputOpen) {
			if (i == 0) {
				this.confirmNameInput();
			} else {
				this.nameInputOpen = false;
				this.namePendingPost = null;
			}
		} else if (this.pendingOverwrite != null) {
			SchematicEntry entry = this.pendingOverwrite;
			this.pendingOverwrite = null;

			if (i == 0 && entry != null) {
				this.beginDownload(entry);
			} else {
				this.status = "";
			}
		}

		Theme.click(i == 0 ? 1.1F : 0.9F);
	}

	private void renderDeleteCollection(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 340);
		int line = this.font.lineHeight;
		String message = "Are you sure you want to delete \"" + this.deleteCollectionName
				+ "\"? It will be lost forever - a very long time.";
		List<String> lines = this.wrap(message, cardWidth - pad * 2 - 4, 4);

		int cardHeight = pad + line + 8 + lines.size() * (line + 2) + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.deleteBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		ctx.fill(x + 1, y + 1, x + 5, y + cardHeight - 1, 0xFFD64545);

		int tx = x + pad + 4;
		int ty = y + pad;
		Theme.text(ctx, this.font, Theme.bold("Delete collection"), tx, ty, Theme.TEXT);
		ty += line + 8;

		for (String row : lines) {
			Theme.text(ctx, this.font, row, tx, ty, Theme.TEXT_ASH);
			ty += line + 2;
		}

		int btnY = y + cardHeight - pad - FIELD_HEIGHT;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int deleteWidth = this.font.width(Theme.bold("Delete")) + 20;
		this.deleteCollectionCancel.set(x + pad, btnY, cancelWidth, FIELD_HEIGHT);
		this.deleteCollectionConfirm.set(x + cardWidth - pad - deleteWidth, btnY, deleteWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.deleteCollectionCancel, "Cancel", mouseX, mouseY, false);

		boolean deleteHover = this.deleteCollectionConfirm.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, this.deleteCollectionConfirm.x, btnY, deleteWidth, FIELD_HEIGHT, Theme.RADIUS_PILL,
				deleteHover ? 0xFFE05555 : 0xFFD64545);
		String deleteLabel = Theme.bold("Delete");
		Theme.text(ctx, this.font, deleteLabel,
				this.deleteCollectionConfirm.x + (deleteWidth - this.font.width(deleteLabel)) / 2,
				btnY + (FIELD_HEIGHT - line) / 2 + 1, Theme.ON_ACCENT);
	}

	private void renderNameInput(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 300);
		int line = this.font.lineHeight;
		int cardHeight = pad + line + 10 + FIELD_HEIGHT + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.nameBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold("New collection"), x + pad, y + pad, Theme.TEXT);

		int fieldY = y + pad + line + 10;
		int fieldWidth = cardWidth - pad * 2;
		Theme.roundedRect(ctx, x + pad, fieldY, fieldWidth, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x + pad, fieldY, fieldWidth, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.ACCENT);

		boolean caret = System.currentTimeMillis() / 500 % 2 == 0;
		String display = this.nameInput.isEmpty() && !caret ? "Name" : this.nameInput + (caret ? "_" : "");
		int textColor = this.nameInput.isEmpty() && !caret ? Theme.TEXT_MUTE : Theme.TEXT;
		Theme.text(ctx, this.font, Theme.clip(this.font, display, fieldWidth - 12), x + pad + 6,
				fieldY + (FIELD_HEIGHT - line) / 2 + 1, textColor);

		int btnY = fieldY + FIELD_HEIGHT + 14;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int createWidth = this.font.width(Theme.bold("Create")) + 20;
		this.nameCancel.set(x + pad, btnY, cancelWidth, FIELD_HEIGHT);
		this.nameConfirm.set(x + cardWidth - pad - createWidth, btnY, createWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.nameCancel, "Cancel", mouseX, mouseY, false);
		this.pillButton(ctx, this.nameConfirm, "Create", mouseX, mouseY, true);
	}

	private void renderDuplicate(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 320);
		int line = this.font.lineHeight;
		List<String> body = this.wrap("This schematic has already been uploaded. You cannot post it again.",
				cardWidth - pad * 2 - 4, 4);
		int cardHeight = pad + line + 8 + body.size() * (line + 2) + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.duplicateBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		ctx.fill(x + 1, y + 1, x + 5, y + cardHeight - 1, 0xFFD64545);

		int tx = x + pad + 4;
		int ty = y + pad;
		Theme.text(ctx, this.font, Theme.bold("Already uploaded"), tx, ty, Theme.TEXT);
		ty += line + 8;

		for (String row : body) {
			Theme.text(ctx, this.font, row, tx, ty, Theme.TEXT_ASH);
			ty += line + 2;
		}

		int btnY = y + cardHeight - pad - FIELD_HEIGHT;
		int closeWidth = this.font.width(Theme.bold("Close")) + 20;
		this.duplicateClose.set(x + cardWidth - pad - closeWidth, btnY, closeWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.duplicateClose, "Close", mouseX, mouseY, true);
	}

	private void renderLoadCode(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 300);
		int line = this.font.lineHeight;
		int cardHeight = pad + line + 10 + FIELD_HEIGHT + 8 + line + 12 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.loadCodeBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold("Load a collection code"), x + pad, y + pad, Theme.TEXT);

		int fieldY = y + pad + line + 10;
		int fieldWidth = cardWidth - pad * 2;
		Theme.roundedRect(ctx, x + pad, fieldY, fieldWidth, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x + pad, fieldY, fieldWidth, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.ACCENT);

		boolean caret = System.currentTimeMillis() / 500 % 2 == 0;
		String display = this.loadCodeInput.isEmpty() && !caret ? "5-character code" : this.loadCodeInput + (caret ? "_" : "");
		int textColor = this.loadCodeInput.isEmpty() && !caret ? Theme.TEXT_MUTE : Theme.TEXT;
		Theme.text(ctx, this.font, display, x + pad + 6, fieldY + (FIELD_HEIGHT - line) / 2 + 1, textColor);

		int statusY = fieldY + FIELD_HEIGHT + 8;

		if (!this.loadCodeStatus.isEmpty()) {
			Theme.text(ctx, this.font, Theme.clip(this.font, this.loadCodeStatus, fieldWidth), x + pad, statusY,
					Theme.ACCENT_BRIGHT);
		}

		int btnY = statusY + line + 12;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int loadWidth = this.font.width(Theme.bold("Load")) + 20;
		this.loadCodeCancel.set(x + pad, btnY, cancelWidth, FIELD_HEIGHT);
		this.loadCodeConfirm.set(x + cardWidth - pad - loadWidth, btnY, loadWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.loadCodeCancel, "Cancel", mouseX, mouseY, false);
		this.pillButton(ctx, this.loadCodeConfirm, "Load", mouseX, mouseY, true);
	}

	private void openCollectionOptions(String name) {
		this.collectionOptionsOpen = true;
		this.collectionOptionsName = name;
		this.modalFocus = -1;
	}

	private void renderCollectionOptions(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 300);
		int line = this.font.lineHeight;
		int cardHeight = pad + line + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.collectionOptionsBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, this.collectionOptionsName, cardWidth - pad * 2)),
				x + pad, y + pad, Theme.TEXT);

		int btnY = y + cardHeight - pad - FIELD_HEIGHT;
		int renameWidth = this.font.width(Theme.bold("Rename")) + 20;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int deleteWidth = this.font.width(Theme.bold("Delete")) + 20;
		this.collectionOptionsRename.set(x + pad, btnY, renameWidth, FIELD_HEIGHT);
		this.collectionOptionsCancel.set(x + pad + renameWidth + 8, btnY, cancelWidth, FIELD_HEIGHT);
		this.collectionOptionsDelete.set(x + cardWidth - pad - deleteWidth, btnY, deleteWidth, FIELD_HEIGHT);

		this.pillButton(ctx, this.collectionOptionsRename, "Rename", mouseX, mouseY, true);
		this.pillButton(ctx, this.collectionOptionsCancel, "Cancel", mouseX, mouseY, false);

		boolean deleteHover = this.collectionOptionsDelete.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, this.collectionOptionsDelete.x, btnY, deleteWidth, FIELD_HEIGHT, Theme.RADIUS_PILL,
				deleteHover ? 0xFFE05555 : 0xFFD64545);
		String deleteLabel = Theme.bold("Delete");
		Theme.text(ctx, this.font, deleteLabel,
				this.collectionOptionsDelete.x + (deleteWidth - this.font.width(deleteLabel)) / 2,
				btnY + (FIELD_HEIGHT - line) / 2 + 1, Theme.ON_ACCENT);
	}

	private void openRenameCollection(String from) {
		this.renameCollectionOpen = true;
		this.renameCollectionFrom = from;
		this.renameInput = from;
		this.modalFocus = -1;
	}

	private void confirmRename() {
		if (this.renameCollectionFrom == null) {
			return;
		}

		String cleaned = CollectionStore.normalize(this.renameInput);

		if (!cleaned.isEmpty() && CollectionStore.rename(this.renameCollectionFrom, cleaned)) {
			if (this.renameCollectionFrom.equals(this.activeCollection)) {
				this.activeCollection = cleaned;
			}

			this.layoutChips();
			this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
			this.refilter();
		}

		this.renameCollectionOpen = false;
		this.renameCollectionFrom = null;
	}

	private void renderRenameCollection(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 300);
		int line = this.font.lineHeight;
		int cardHeight = pad + line + 10 + FIELD_HEIGHT + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.renameBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold("Rename collection"), x + pad, y + pad, Theme.TEXT);

		int fieldY = y + pad + line + 10;
		int fieldWidth = cardWidth - pad * 2;
		Theme.roundedRect(ctx, x + pad, fieldY, fieldWidth, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x + pad, fieldY, fieldWidth, FIELD_HEIGHT, Theme.RADIUS_PILL, Theme.ACCENT);

		boolean caret = System.currentTimeMillis() / 500 % 2 == 0;
		String display = this.renameInput.isEmpty() && !caret ? "Name" : this.renameInput + (caret ? "_" : "");
		int textColor = this.renameInput.isEmpty() && !caret ? Theme.TEXT_MUTE : Theme.TEXT;
		Theme.text(ctx, this.font, Theme.clip(this.font, display, fieldWidth - 12), x + pad + 6,
				fieldY + (FIELD_HEIGHT - line) / 2 + 1, textColor);

		int btnY = fieldY + FIELD_HEIGHT + 14;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int renameWidth = this.font.width(Theme.bold("Rename")) + 20;
		this.renameCancel.set(x + pad, btnY, cancelWidth, FIELD_HEIGHT);
		this.renameConfirm.set(x + cardWidth - pad - renameWidth, btnY, renameWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.renameCancel, "Cancel", mouseX, mouseY, false);
		this.pillButton(ctx, this.renameConfirm, "Rename", mouseX, mouseY, true);
	}

	private void openPostOptions(String id) {
		SchematicEntry entry = null;

		for (SchematicEntry e : this.myPosts) {
			if (e.id().equals(id)) {
				entry = e;
				break;
			}
		}

		if (entry == null) {
			return;
		}

		this.postOptionsOpen = true;
		this.postOptionsId = id;
		this.postOptionsTitle = entry.title();
	}

	private void renderPostOptions(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 300);
		int line = this.font.lineHeight;
		int cardHeight = pad + line + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.postOptionsBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, this.postOptionsTitle, cardWidth - pad * 2)),
				x + pad, y + pad, Theme.TEXT);

		int btnY = y + cardHeight - pad - FIELD_HEIGHT;
		int editWidth = this.font.width(Theme.bold("Edit")) + 20;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int unpubWidth = this.font.width(Theme.bold("Unpublish")) + 20;
		this.postOptionsEdit.set(x + pad, btnY, editWidth, FIELD_HEIGHT);
		this.postOptionsCancel.set(x + pad + editWidth + 8, btnY, cancelWidth, FIELD_HEIGHT);
		this.postOptionsUnpublish.set(x + cardWidth - pad - unpubWidth, btnY, unpubWidth, FIELD_HEIGHT);

		this.pillButton(ctx, this.postOptionsEdit, "Edit", mouseX, mouseY, true);
		this.pillButton(ctx, this.postOptionsCancel, "Cancel", mouseX, mouseY, false);

		boolean unpubHover = this.postOptionsUnpublish.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, this.postOptionsUnpublish.x, btnY, unpubWidth, FIELD_HEIGHT, Theme.RADIUS_PILL,
				unpubHover ? 0xFFE05555 : 0xFFD64545);
		String unpubLabel = Theme.bold("Unpublish");
		Theme.text(ctx, this.font, unpubLabel,
				this.postOptionsUnpublish.x + (unpubWidth - this.font.width(unpubLabel)) / 2,
				btnY + (FIELD_HEIGHT - line) / 2 + 1, Theme.ON_ACCENT);
	}

	private void openEditPost(String id) {
		SchematicEntry entry = null;

		for (SchematicEntry e : this.myPosts) {
			if (e.id().equals(id)) {
				entry = e;
				break;
			}
		}

		if (entry == null) {
			return;
		}

		this.editPostOpen = true;
		this.editPostId = id;
		this.editStatus = "";
		this.editCategory = entry.category();
		this.fillEditField(this.editTitleBox, entry.title());
		this.fillEditField(this.editThumbnailBox, entry.thumbnailName() == null ? "" : entry.thumbnailName());
		this.fillEditField(this.editDesignerBox, entry.designer() == null ? "" : entry.designer());
		this.fillEditField(this.editDescriptionBox, entry.description() == null ? "" : entry.description());
		this.setFocused(null);
	}

	// Pre-fills a field and parks the cursor at the start, so a long value shows from the
	// beginning instead of scrolled to its end.
	private void fillEditField(EditBox box, String value) {
		box.setValue(value);
		box.setCursorPosition(0);
		box.setHighlightPos(0);
	}

	private void renderEditPost(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 320);
		int line = this.font.lineHeight;
		int fieldGap = 8;
		int cardHeight = pad + line + 10 + (FIELD_HEIGHT + fieldGap) * 5 + 6 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.editBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold("Edit post"), x + pad, y + pad, Theme.TEXT);

		int fx = x + pad;
		int fw = cardWidth - pad * 2;
		int fy = y + pad + line + 10;

		this.field(ctx, this.editTitleBox, fx, fy, fw, mouseX, mouseY, partialTick);
		fy += FIELD_HEIGHT + fieldGap;
		this.field(ctx, this.editThumbnailBox, fx, fy, fw, mouseX, mouseY, partialTick);
		fy += FIELD_HEIGHT + fieldGap;
		this.field(ctx, this.editDesignerBox, fx, fy, fw, mouseX, mouseY, partialTick);
		fy += FIELD_HEIGHT + fieldGap;
		this.field(ctx, this.editDescriptionBox, fx, fy, fw, mouseX, mouseY, partialTick);
		fy += FIELD_HEIGHT + fieldGap;

		this.editCategoryButton.set(fx, fy, fw, FIELD_HEIGHT);
		this.pillButton(ctx, this.editCategoryButton, "Category: " + this.editCategory.label(), mouseX, mouseY, false);
		fy += FIELD_HEIGHT + 6;

		if (!this.editStatus.isEmpty()) {
			Theme.text(ctx, this.font, Theme.clip(this.font, this.editStatus, fw), fx, fy - 2, Theme.ACCENT_BRIGHT);
		}

		int btnY = y + cardHeight - pad - FIELD_HEIGHT;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int saveWidth = this.font.width(Theme.bold("Save")) + 20;
		this.editCancel.set(fx, btnY, cancelWidth, FIELD_HEIGHT);
		this.editSave.set(x + cardWidth - pad - saveWidth, btnY, saveWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.editCancel, "Cancel", mouseX, mouseY, false);
		this.pillButton(ctx, this.editSave, "Save", mouseX, mouseY, true);
	}

	private void confirmEdit() {
		String code = UploaderAccess.code();
		String id = this.editPostId;

		if (code == null || id == null) {
			return;
		}

		String title = this.editTitleBox.getValue().trim();

		if (title.isEmpty()) {
			this.editStatus = "A title is required.";
			return;
		}

		this.editStatus = "Saving...";
		String thumbnail = this.editThumbnailBox.getValue().trim();
		String designer = this.editDesignerBox.getValue().trim();
		String description = this.editDescriptionBox.getValue().trim();
		String category = this.editCategory.name();

		Thread worker = new Thread(() -> {
			int status = Backend.editPost(code, id, title, thumbnail, designer, description, category);
			Minecraft.getInstance().execute(() -> {
				if (status / 100 == 2) {
					this.editPostOpen = false;
					this.editPostId = null;
					this.myStatsLoaded = false;
					Catalogue.refresh();
					Toasts.push("Post updated", title, new ItemStack(Items.WRITABLE_BOOK));
				} else {
					this.editStatus = "Could not save. Try again.";
				}
			});
		}, "schematicindex-edit");
		worker.setDaemon(true);
		worker.start();
	}

	private void renderUnpublish(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 320);
		int line = this.font.lineHeight;
		List<String> lines = this.wrap("Unpublish \"" + this.unpublishTitle
				+ "\"? It will be removed from the index. You cannot undo this action.", cardWidth - pad * 2 - 4, 4);
		int cardHeight = pad + line + 8 + lines.size() * (line + 2) + 14 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.unpublishBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		ctx.fill(x + 1, y + 1, x + 5, y + cardHeight - 1, 0xFFD64545);

		int tx = x + pad + 4;
		int ty = y + pad;
		Theme.text(ctx, this.font, Theme.bold("Unpublish post"), tx, ty, Theme.TEXT);
		ty += line + 8;

		for (String row : lines) {
			Theme.text(ctx, this.font, row, tx, ty, Theme.TEXT_ASH);
			ty += line + 2;
		}

		int btnY = y + cardHeight - pad - FIELD_HEIGHT;
		int cancelWidth = this.font.width(Theme.bold("Cancel")) + 20;
		int confirmWidth = this.font.width(Theme.bold("Unpublish")) + 20;
		this.unpublishCancel.set(x + pad, btnY, cancelWidth, FIELD_HEIGHT);
		this.unpublishConfirm.set(x + cardWidth - pad - confirmWidth, btnY, confirmWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.unpublishCancel, "Cancel", mouseX, mouseY, false);

		boolean hover = this.unpublishConfirm.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, this.unpublishConfirm.x, btnY, confirmWidth, FIELD_HEIGHT, Theme.RADIUS_PILL,
				hover ? 0xFFE05555 : 0xFFD64545);
		String label = Theme.bold("Unpublish");
		Theme.text(ctx, this.font, label, this.unpublishConfirm.x + (confirmWidth - this.font.width(label)) / 2,
				btnY + (FIELD_HEIGHT - line) / 2 + 1, Theme.ON_ACCENT);
	}

	private void confirmUnpublish() {
		String code = UploaderAccess.code();
		String id = this.unpublishId;
		this.unpublishOpen = false;

		if (code == null || id == null) {
			return;
		}

		Thread worker = new Thread(() -> {
			int status = Backend.unpublishPost(code, id);
			Minecraft.getInstance().execute(() -> {
				if (status / 100 == 2) {
					this.myStatsLoaded = false;
					Catalogue.refresh();
					Toasts.push("Post unpublished", "No one can view it any longer.", new ItemStack(Items.BARRIER));
				} else {
					this.showError(Errors.UPLOAD);
				}
			});
		}, "schematicindex-unpublish");
		worker.setDaemon(true);
		worker.start();
	}

	public void showError(String code) {
		this.errorCode = code;
		this.errorOpen = true;
		this.modalFocus = -1;
	}

	private void renderError(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 16;
		int cardWidth = Math.min(this.width - 40, 330);
		int line = this.font.lineHeight;
		int innerWidth = cardWidth - pad * 2 - 4;

		String discord = RemoteContent.discord();
		boolean hasDiscord = discord != null && !discord.isBlank();

		List<String> desc = this.wrap("Report this bug using the code you were given in the discord server below.",
				innerWidth, 3);

		int cardHeight = pad + line + 8 + line + 10 + desc.size() * (line + 2) + 8 + line + 16 + FIELD_HEIGHT + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.errorBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		ctx.fill(x + 1, y + 1, x + 5, y + cardHeight - 1, 0xFFD64545);

		int tx = x + pad + 4;
		int ty = y + pad;

		Theme.text(ctx, this.font, Theme.bold("Something went wrong"), tx, ty, Theme.TEXT);
		ty += line + 8;

		Theme.text(ctx, this.font, "Error code:", tx, ty, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, Theme.bold(this.errorCode), tx + this.font.width("Error code: "), ty, Theme.TEXT);
		ty += line + 10;

		for (String row : desc) {
			Theme.text(ctx, this.font, row, tx, ty, Theme.TEXT_ASH);
			ty += line + 2;
		}

		ty += 8;

		if (hasDiscord) {
			String linkText = Theme.clip(this.font, discord, innerWidth);
			int linkWidth = this.font.width(linkText);
			this.errorReport.set(tx, ty, linkWidth, line);
			boolean hover = this.errorReport.contains(mouseX, mouseY);
			int color = hover ? Theme.ACCENT_BRIGHT : Theme.ACCENT;
			Theme.text(ctx, this.font, linkText, tx, ty, color);
			ctx.fill(tx, ty + line - 1, tx + linkWidth, ty + line, color);
		} else {
			this.errorReport.set(0, 0, 0, 0);
			Theme.text(ctx, this.font, "Discord link coming soon.", tx, ty, Theme.TEXT_MUTE);
		}

		int closeWidth = this.font.width(Theme.bold("Close")) + 20;
		int closeY = y + cardHeight - pad - FIELD_HEIGHT;
		this.errorClose.set(x + cardWidth - pad - closeWidth, closeY, closeWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.errorClose, "Close", mouseX, mouseY, false);
	}

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

		int bodyTop = y + pad + line + 6;
		int bodyBottom = y + cardHeight - pad - 16 - 8;
		int bodyWidth = cardWidth - pad * 2;

		List<String> body = this.wrapParagraphs(this.effectiveTermsBody(), bodyWidth - 6);
		int contentHeight = body.size() * (line + 1);
		int viewport = bodyBottom - bodyTop;
		this.tosMaxScroll = Math.max(0.0F, contentHeight - viewport);
		this.tosScroll = Math.max(0.0F, Math.min(this.tosMaxScroll, this.tosScroll));

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

		if (this.tosMaxScroll > 0.0F) {
			int trackX = x + cardWidth - pad + 2;
			Theme.roundedRect(ctx, trackX, bodyTop, 2, viewport, 1, Theme.SURFACE_CARD);
			int thumbHeight = Math.max(12, Math.round((float) viewport * viewport / contentHeight));
			int thumbY = bodyTop + Math.round((viewport - thumbHeight) * (this.tosScroll / this.tosMaxScroll));
			this.drawScrollThumb(ctx, SCROLLBAR_TOS, trackX, 2, thumbY, thumbHeight, 1, bodyTop, viewport,
					Theme.ACCENT_BRIGHT);
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
			this.disabledPill(ctx, this.tosDecline, "Decline");
			this.disabledPill(ctx, this.tosAgree, "I Agree");
			String hint = "Scroll down to continue";
			Theme.text(ctx, this.font, hint, x + (cardWidth - this.font.width(hint)) / 2, buttonY + 4, Theme.TEXT_ASH);
		}
	}

	private void disabledPill(GuiGraphics ctx, Rect rect, String label) {
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.SURFACE_CARD);
		String text = Theme.bold(label);
		Theme.text(ctx, this.font, text, rect.x + (rect.width - this.font.width(text)) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.TEXT_ASH);
	}

	// The hard lock takes over the whole screen (UpdateScreen); a notify-only update is far
	// gentler - a single toast the first time the catalogue is opened in a session.
	private void maybeNotifyUpdate() {
		if (updateNoticeShown || UpdateGate.locked() || !UpdateGate.shouldPrompt()) {
			return;
		}
		updateNoticeShown = true;
		Toasts.push("Update available",
				"Version " + UpdateGate.requiredVersion() + " is on Modrinth.",
				new ItemStack(Items.NAME_TAG));
	}

	private void clickTos(double mouseX, double mouseY) {
		if (!this.tosScrolledBottom) {
			return;
		}

		if (this.tosAgree.contains(mouseX, mouseY)) {
			Settings.acceptTerms();
			// Nothing contacts the server before this point; start now that consent is given.
			Catalogue.refresh();
			UpdateGate.checkAsync();
			this.tosOpen = false;
			Theme.click(1.2F);
			this.maybeStartTutorial();
		} else if (this.tosDecline.contains(mouseX, mouseY)) {
			Settings.revokeTerms();
			this.onClose();
		}
	}

	private int @Nullable [] tutorialTarget() {
		return switch (this.tutorialStep) {
			case 1 -> {
				int top = this.railRects.isEmpty() ? TOP_BAR_HEIGHT : this.railRects.get(0).y - 3;
				int bottom = this.railRects.isEmpty() ? this.height
						: this.railRects.get(this.railRects.size() - 1).y
								+ this.railRects.get(this.railRects.size() - 1).height + 3;
				yield new int[]{0, top, RAIL_WIDTH, bottom - top};
			}

			case 2 -> new int[]{RAIL_WIDTH, TOP_BAR_HEIGHT, this.width - RAIL_WIDTH, this.chipRowHeight};

			case 3 -> {
				int searchWidth = this.searchWidth();
				int searchX = this.contentX + (this.contentWidth - searchWidth) / 2;
				int searchY = (TOP_BAR_HEIGHT - 16) / 2;
				yield new int[]{searchX - 2, searchY - 2, searchWidth + 4, 20};
			}

			case 4 -> new int[]{this.contentX, this.gridTop, this.cardWidth, this.cardHeight};
			default -> null;
		};
	}

	private void renderTutorial(GuiGraphics ctx, int mouseX, int mouseY) {
		int[] target = this.tutorialTarget();

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

	private void renderTopBar(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, TOP_BAR_HEIGHT, Theme.SURFACE);
		ctx.fill(0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT, Theme.HAIRLINE);

		int searchWidth = this.searchWidth();
		int searchX = this.contentX + (this.contentWidth - searchWidth) / 2;
		int titleX = OUTER_MARGIN;
		int titleRoom = searchX - titleX - 8;

		if (titleRoom >= this.font.width(Theme.bold(TITLE_TEXT)) * 2) {
			this.drawGradientTitle(ctx, TITLE_TEXT, 0, titleX, 7, 2.0F);
		} else if (titleRoom >= this.font.width(TITLE_TEXT)) {
			this.drawGradientTitle(ctx, TITLE_TEXT, 0, titleX, 12, 1.0F);
		} else if (titleRoom > 0) {
			this.drawGradientTitle(ctx, "Index", 14, titleX, 12, 1.0F);
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

	private static final String TITLE_TEXT = "The Schematic Index";
	private static final int[] TITLE_COLORS = {
			0xFFA1A1A1, 0xFFB2B2B2, 0xFFC3C3C3, 0,
			0xFFD4D4D4, 0xFFE5E5E5, 0xFFF6F6F6, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0,
			0xFF1B503B, 0xFF23664B, 0xFF2A7B5B, 0xFF34976F, 0xFF3DB283,
	};

	private void drawGradientTitle(GuiGraphics ctx, String text, int startIndex, int x, int y, float scale) {
		ctx.pose().pushMatrix();
		ctx.pose().translate((float) x, (float) y);
		ctx.pose().scale(scale, scale);

		int cx = 0;

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			String glyph = "§l" + c;

			if (c != ' ') {
				ctx.drawString(this.font, glyph, cx, 0, TITLE_COLORS[startIndex + i], false);
			}

			cx += this.font.width(glyph);
		}

		ctx.pose().popMatrix();
	}

	private boolean gridPage() {
		return this.page == Page.BROWSE || this.page == Page.SAVED;
	}

	private boolean partnerReserve() {
		return this.gridPage() && !RemoteContent.partners().isEmpty();
	}

	private void layoutContent() {
		int rightRail = this.partnerReserve() ? PARTNER_RAIL_WIDTH : 0;
		int available = this.width - RAIL_WIDTH - rightRail - OUTER_MARGIN * 2;
		this.contentWidth = Math.min(available, CONTENT_MAX_WIDTH);
		this.contentX = RAIL_WIDTH + OUTER_MARGIN + (available - this.contentWidth) / 2;

		this.columns = Math.max(2, Math.min(7, columnsFor(this.contentWidth) + Settings.gridDensity()));
		this.cardWidth = (this.contentWidth - GUTTER * (this.columns - 1)) / this.columns;
		this.cardHeight = imageHeight(this.cardWidth) + CAPTION_HEIGHT;
	}

	private void relayout() {
		this.layoutContent();

		int searchW = this.searchWidth();
		int searchX = this.contentX + (this.contentWidth - searchW) / 2;
		int searchY = (TOP_BAR_HEIGHT - 16) / 2;

		if (this.searchBox != null) {
			this.searchBox.setX(searchX + 6);
			this.searchBox.setWidth(searchW - 12);
		}

		this.closeButton.set(this.contentX + this.contentWidth - 38, searchY, 38, 16);
		this.layoutChips();
		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.refilter();
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

			float hover = Theme.buttonHover(rect, hovered);
			float scale = Theme.popScale(rect, 1.0F + RAIL_HOVER_GROW * hover);
			Theme.pushScale(ctx, iconX - 2, iconY - 2, 20, 20, scale);

			Theme.roundedRect(ctx, iconX - 2, iconY - 2, 20, 20, Theme.RADIUS_PILL,
					active ? Theme.RAIL_TILE_ACTIVE : Theme.RAIL_TILE);
			ctx.renderItem(pages[i].icon(), iconX, iconY);
			Theme.pop(ctx);

			if (hovered && !active) {
				String label = pages[i].label;
				int width = this.font.width(label) + 8;
				Theme.roundedRect(ctx, RAIL_WIDTH + 2, rect.y + 8, width, 12, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
				Theme.text(ctx, this.font, label, RAIL_WIDTH + 6, rect.y + 10, Theme.TEXT);
			}
		}

		this.renderRailLinks(ctx, mouseX, mouseY);
	}

	private void renderRailLinks(GuiGraphics ctx, int mouseX, int mouseY) {
		List<RemoteContent.Link> links = RemoteContent.links();
		this.railLinkRects.clear();

		if (links.isEmpty()) {
			return;
		}

		int item = 26;
		int size = 18;
		int y = this.height - 8 - links.size() * item;

		for (RemoteContent.Link link : links) {
			Rect rect = new Rect();
			rect.set(0, y, RAIL_WIDTH, item);
			this.railLinkRects.add(rect);

			boolean hovered = rect.contains(mouseX, mouseY);

			if (hovered) {
				ctx.fill(rect.x, rect.y, rect.x + rect.width - 1, rect.y + rect.height, Theme.SURFACE_ELEVATED);
			}

			int iconX = rect.x + (RAIL_WIDTH - size) / 2;
			int iconY = rect.y + (item - size) / 2;
			Identifier icon = ImageStore.avatar(link.iconUrl());

			if (icon != null) {
				Theme.image(ctx, icon, iconX, iconY, size, size, 64, 64);
			} else {
				Theme.roundedRect(ctx, iconX, iconY, size, size, 4, Theme.RAIL_TILE);
			}

			if (hovered && !link.label().isBlank()) {
				int width = this.font.width(link.label()) + 8;
				Theme.roundedRect(ctx, RAIL_WIDTH + 2, rect.y + (item - 12) / 2, width, 12, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
				Theme.text(ctx, this.font, link.label(), RAIL_WIDTH + 6, rect.y + (item - 12) / 2 + 2, Theme.TEXT);
			}

			y += item;
		}
	}

	private void renderPartnerRail(GuiGraphics ctx, int mouseX, int mouseY) {
		List<RemoteContent.Partner> partners = RemoteContent.partners();
		this.partnerRects.clear();

		if (partners.isEmpty()) {
			return;
		}

		int railX = this.width - PARTNER_RAIL_WIDTH;
		ctx.fill(railX, TOP_BAR_HEIGHT, this.width, this.height, Theme.SURFACE);
		ctx.fill(railX, TOP_BAR_HEIGHT, railX + 1, this.height, Theme.HAIRLINE);

		int centreX = railX + PARTNER_RAIL_WIDTH / 2;

		String header = "Partners";
		Theme.text(ctx, this.font, header, centreX - this.font.width(header) / 2, TOP_BAR_HEIGHT + 8, Theme.TEXT_MUTE);

		int item = RAIL_ITEM_HEIGHT;
		int size = 22;
		int y = TOP_BAR_HEIGHT + 22;

		for (RemoteContent.Partner partner : partners) {
			if (y > this.height) {
				break;
			}

			Rect rect = new Rect();
			rect.set(railX, y, PARTNER_RAIL_WIDTH, item);
			this.partnerRects.add(rect);

			boolean hovered = rect.contains(mouseX, mouseY);
			int iconX = centreX - size / 2;
			int iconY = y + (item - size) / 2;

			if (hovered) {
				Theme.roundedRect(ctx, iconX - 3, iconY - 3, size + 6, size + 6, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
			}

			Identifier texture = ImageStore.avatar(partner.iconUrl());

			if (texture != null) {
				Theme.image(ctx, texture, iconX, iconY, size, size, 64, 64);
			} else {
				Theme.roundedRect(ctx, iconX, iconY, size, size, 5, Theme.RAIL_TILE);
			}

			if (hovered && !partner.name().isBlank()) {
				int width = this.font.width(partner.name()) + 8;
				int tipX = railX - width - 4;
				int tipY = y + (item - 12) / 2;
				Theme.roundedRect(ctx, tipX, tipY, width, 12, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
				Theme.text(ctx, this.font, partner.name(), tipX + 4, tipY + 2, Theme.TEXT);
			}

			y += item;
		}
	}

	private void pillButton(GuiGraphics ctx, Rect rect, String label, int mouseX, int mouseY, boolean primary) {
		boolean hovered = rect.contains(mouseX, mouseY);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.buttonScale(rect, 1.0F + Theme.HOVER_SCALE * hover);

		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

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

	private void downloadButton(GuiGraphics ctx, Rect rect, SchematicEntry entry, int mouseX, int mouseY) {
		boolean hovered = !this.detailDownloadLocked && rect.contains(mouseX, mouseY);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.buttonScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);

		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				Theme.lighten(Theme.ACCENT, 0.12F * hover));

		Download.Progress progress = Download.progress(entry.id());
		String label = "Download";

		if (progress != null) {
			Download.State previous = this.downloadStates.get(entry.id());

			if (progress.state() != previous) {
				if (previous != null) {
					if (progress.state() == Download.State.DONE) {
						Theme.success();
						this.status = "";
					} else if (progress.state() == Download.State.FAILED) {
						Theme.failure();
						this.status = "Download failed. Press Retry to try again.";
						this.detailDownloadLocked = false;
						this.showError(Errors.DOWNLOAD);
					}
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
				case DONE -> "Downloaded";
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

		if (this.profilePoster != null) {
			this.renderProfileHeader(ctx, mouseX, mouseY);
			return;
		}

		if (this.page == Page.SAVED) {
			this.renderCollectionChips(ctx, mouseX, mouseY);
			this.pillButton(ctx, this.sortButton, "Sort: " + this.sort.label(), mouseX, mouseY, false);
			return;
		}

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

		this.pillButton(ctx, this.downloadFilterButton, this.downloadFilterLabel(), mouseX, mouseY,
				this.downloadFilter != 0);
		this.pillButton(ctx, this.sortButton, "Sort: " + this.sort.label(), mouseX, mouseY, false);
	}

	private void renderCollectionChips(GuiGraphics ctx, int mouseX, int mouseY) {
		List<String> names = CollectionStore.names();

		for (int i = 0; i < this.collectionChips.size(); i++) {
			Rect rect = this.collectionChips.get(i);

			if (i == 0) {
				this.chipPill(ctx, rect, "All saved", this.activeCollection == null, mouseX, mouseY);
				continue;
			}

			String label = i - 1 < names.size() ? names.get(i - 1) : "";
			this.namedCollectionChip(ctx, rect, label, label.equals(this.activeCollection), mouseX, mouseY);
		}

		this.chipPill(ctx, this.newCollectionChip, "+ New", false, mouseX, mouseY);

		if (this.activeCollection != null) {
			this.chipPill(ctx, this.addPostsChip, "+ Add posts", false, mouseX, mouseY);
			// Once generated, the chip shows the code (in accent) and copies it when clicked.
			this.chipPill(ctx, this.generateCodeChip, this.generateCodeLabel(), this.sharedCode != null, mouseX, mouseY);
		}

		this.chipPill(ctx, this.loadCodeChip, "Load code", false, mouseX, mouseY);
	}

	private void chipPill(GuiGraphics ctx, Rect rect, String label, boolean active, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		int fill = active ? Theme.ACCENT : (hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.popScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);
		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, label, rect.width - 8)), rect.x + 6,
				rect.y + (CHIP_HEIGHT - this.font.lineHeight) / 2 + 1, active ? Theme.ON_ACCENT : Theme.TEXT_MUTE);
		Theme.pop(ctx);
	}

	// A collection chip with a trash icon on the right; the icon turns red on hover.
	private void namedCollectionChip(GuiGraphics ctx, Rect rect, String label, boolean active, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		int fill = active ? Theme.ACCENT : (hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
		float hover = Theme.buttonHover(rect, hovered);
		float scale = Theme.popScale(rect, 1.0F + Theme.HOVER_SCALE * hover);
		Theme.pushScale(ctx, rect.x, rect.y, rect.width, rect.height, scale);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);

		int labelRoom = rect.width - 8 - TRASH_CELL_WIDTH;
		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, label, labelRoom)), rect.x + 6,
				rect.y + (CHIP_HEIGHT - this.font.lineHeight) / 2 + 1, active ? Theme.ON_ACCENT : Theme.TEXT_MUTE);

		Rect trash = this.trashRect(rect);
		boolean trashHovered = trash.contains(mouseX, mouseY);
		int trashColor = trashHovered ? 0xFFE05555 : (active ? Theme.ON_ACCENT : Theme.TEXT_MUTE);
		Theme.trashGlyph(ctx, trash.x + (trash.width - Theme.TRASH_GLYPH_WIDTH) / 2,
				trash.y + (trash.height - 8) / 2, trashColor);
		Theme.pop(ctx);
	}

	// Hit rect of the trash icon sitting in the right cell of a named collection chip.
	private Rect trashRect(Rect chip) {
		Rect r = new Rect();
		r.set(chip.x + chip.width - TRASH_CELL_WIDTH, chip.y, TRASH_CELL_WIDTH, chip.height);
		return r;
	}

	private void renderAddModeBanner(GuiGraphics ctx, int mouseX, int mouseY) {
		if (this.addingToCollection == null) {
			return;
		}

		int height = 20;
		int y = TOP_BAR_HEIGHT + this.chipRowHeight;
		ctx.fill(RAIL_WIDTH, y, this.width, y + height, Theme.ACCENT);

		String text = "Tap posts to add them to \"" + this.addingToCollection + "\"";
		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, text, this.contentWidth - 90)),
				this.contentX, y + (height - this.font.lineHeight) / 2, Theme.ON_ACCENT);

		int doneWidth = this.font.width(Theme.bold("Done")) + 16;
		this.addModeDone.set(this.contentX + this.contentWidth - doneWidth, y + 2, doneWidth, height - 4);
		boolean hovered = this.addModeDone.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, this.addModeDone.x, this.addModeDone.y, doneWidth, height - 4, Theme.RADIUS_PILL,
				hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
		Theme.text(ctx, this.font, Theme.bold("Done"), this.addModeDone.x + 8, this.addModeDone.y + 2, Theme.TEXT);
	}

	private void renderCollectionMenu(GuiGraphics ctx, int mouseX, int mouseY, SchematicEntry entry) {
		this.collectionMenuRects.clear();
		List<String> names = CollectionStore.names();

		int rowH = 15;
		int menuWidth = 160;
		int menuHeight = (names.size() + 1) * rowH + 8;
		int mx = this.detailCollection.x;
		int my = this.detailCollection.y - menuHeight - 4;

		if (my < 4) {
			my = this.detailCollection.y + 18;
		}

		if (mx + menuWidth > this.width - 4) {
			mx = this.width - menuWidth - 4;
		}

		Theme.roundedRect(ctx, mx, my, menuWidth, menuHeight, Theme.RADIUS_CARD, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, mx, my, menuWidth, menuHeight, Theme.RADIUS_CARD, Theme.HAIRLINE);

		int cy = my + 4;

		for (String name : names) {
			Rect rect = new Rect();
			rect.set(mx + 4, cy, menuWidth - 8, rowH);
			this.collectionMenuRects.add(rect);

			boolean in = CollectionStore.contains(name, entry.id());
			boolean hovered = rect.contains(mouseX, mouseY);

			if (in || hovered) {
				Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.SURFACE_CARD);
			}

			if (in) {
				ctx.fill(rect.x, rect.y + 2, rect.x + 3, rect.y + rect.height - 2, Theme.ACCENT);
			}

			Theme.text(ctx, this.font, Theme.clip(this.font, name, menuWidth - 16), mx + (in ? 12 : 8), cy + 3,
					in ? Theme.ACCENT_BRIGHT : Theme.TEXT);
			cy += rowH;
		}

		this.collectionMenuNew.set(mx + 4, cy, menuWidth - 8, rowH);

		if (this.collectionMenuNew.contains(mouseX, mouseY)) {
			Theme.roundedRect(ctx, this.collectionMenuNew.x, cy, menuWidth - 8, rowH, Theme.RADIUS_PILL, Theme.SURFACE_CARD);
		}

		Theme.text(ctx, this.font, Theme.bold("+ New collection"), mx + 8, cy + 3, Theme.ACCENT);
	}

	private boolean clickCollectionMenu(double mouseX, double mouseY, SchematicEntry entry) {
		List<String> names = CollectionStore.names();

		for (int i = 0; i < this.collectionMenuRects.size() && i < names.size(); i++) {
			if (this.collectionMenuRects.get(i).contains(mouseX, mouseY)) {
				boolean nowIn = CollectionStore.toggle(names.get(i), entry.id());
				Theme.click(nowIn ? 1.2F : 0.9F);

				if (this.page == Page.SAVED) {
					this.refilter();
				}

				return true;
			}
		}

		if (this.collectionMenuNew.contains(mouseX, mouseY)) {
			Theme.click(1.1F);
			this.collectionMenuOpen = false;
			this.openNameInput(entry.id());
			return true;
		}

		this.collectionMenuOpen = false;
		return true;
	}

	private void renderProfileHeader(GuiGraphics ctx, int mouseX, int mouseY) {
		int pad = 8;
		int y = TOP_BAR_HEIGHT + pad;

		int backWidth = this.font.width(Theme.bold("< Back")) + 12;
		this.profileBack.set(this.contentX, y, backWidth, 13);
		this.pillButton(ctx, this.profileBack, "< Back", mouseX, mouseY, false);

		int avatarSize = 54;
		int avatarX = this.contentX;
		int avatarY = y + 18;
		String ign = this.profileIgn != null && !this.profileIgn.isBlank() ? this.profileIgn : this.profilePoster;
		Identifier avatar = ImageStore.avatar("https://mc-heads.net/avatar/" + Backend.encode(ign) + "/64.png");

		if (avatar != null) {
			Theme.image(ctx, avatar, avatarX, avatarY, avatarSize, avatarSize, 64, 64);
		} else {
			Theme.roundedRect(ctx, avatarX, avatarY, avatarSize, avatarSize, 6, Theme.SURFACE_ELEVATED);
		}

		int statsX = avatarX + avatarSize + 18;
		int statsWidth = this.contentX + this.contentWidth - statsX;
		int column = statsWidth / 3;
		int statTop = avatarY + avatarSize / 2 - this.font.lineHeight;
		this.profileStat(ctx, statsX + column / 2, statTop,
				this.profilePosts < 0 ? "..." : SchematicEntry.compact(this.profilePosts), "Posts");
		this.profileStat(ctx, statsX + column + column / 2, statTop,
				this.profileFollowers < 0 ? "..." : SchematicEntry.compact(this.profileFollowers), "Followers");
		this.profileStat(ctx, statsX + column * 2 + column / 2, statTop,
				this.profileDownloads < 0 ? "..." : SchematicEntry.compact(this.profileDownloads), "Downloads");

		int nameY = avatarY + avatarSize + 6;
		Theme.textScaled(ctx, this.font, Theme.bold(Theme.clip(this.font, this.profilePoster, this.contentWidth)),
				this.contentX, nameY, 1.15F, Theme.TEXT);

		boolean following = Follows.isFollowing(this.profilePoster);
		String followLabel = following ? "Following" : "Follow";
		int followWidth = Math.min(160, this.contentWidth);
		int followY = nameY + Math.round(this.font.lineHeight * 1.15F) + 6;
		this.profileFollow.set(this.contentX, followY, followWidth, 16);
		this.pillButton(ctx, this.profileFollow, followLabel, mouseX, mouseY, !following);
	}

	private void profileStat(GuiGraphics ctx, int centerX, int topY, String value, String label) {
		int valueWidth = this.font.width(Theme.bold(value));
		Theme.text(ctx, this.font, Theme.bold(value), centerX - valueWidth / 2, topY, Theme.TEXT);
		int labelWidth = this.font.width(label);
		Theme.text(ctx, this.font, label, centerX - labelWidth / 2, topY + this.font.lineHeight + 2, Theme.TEXT_MUTE);
	}

	private void openProfile(String poster) {
		this.profilePoster = poster;
		this.profileIgn = null;
		this.profileFollowers = -1;
		this.profilePosts = -1;
		this.profileDownloads = -1;
		this.detail = null;
		this.collectionMenuOpen = false;
		this.page = Page.BROWSE;
		this.category = Category.ALL;
		this.setSearch("");
		this.fetchCreator(poster);
		this.layoutChips();
		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.scroll = 0.0F;
		this.refilter();
	}

	private void closeProfile() {
		this.profilePoster = null;
		this.layoutChips();
		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.scroll = 0.0F;
		this.refilter();
	}

	private void fetchCreator(String poster) {
		Thread worker = new Thread(() -> {
			com.google.gson.JsonObject body = Backend.getJson("/creator/" + Backend.encode(poster));

			if (body == null) {
				return;
			}

			String ign = body.has("ign") && !body.get("ign").isJsonNull() ? body.get("ign").getAsString() : "";
			int followers = body.has("followers") && !body.get("followers").isJsonNull() ? body.get("followers").getAsInt() : 0;
			int posts = body.has("posts") && !body.get("posts").isJsonNull() ? body.get("posts").getAsInt() : 0;
			int downloads = body.has("downloads") && !body.get("downloads").isJsonNull() ? body.get("downloads").getAsInt() : 0;

			Minecraft.getInstance().execute(() -> {
				if (poster.equals(this.profilePoster)) {
					this.profileIgn = ign;
					this.profileFollowers = followers;
					this.profilePosts = posts;
					this.profileDownloads = downloads;
				}
			});
		}, "schematicindex-creator");
		worker.setDaemon(true);
		worker.start();
	}

	private void setSearch(String value) {
		this.query = value;

		if (this.searchBox != null) {
			this.searchBox.setValue(value);
		}
	}

	private boolean clickCollectionChips(double mouseX, double mouseY) {
		List<String> names = CollectionStore.names();

		// Trash icon on a named chip opens the delete warning.
		for (int i = 1; i < this.collectionChips.size(); i++) {
			if (this.trashRect(this.collectionChips.get(i)).contains(mouseX, mouseY)) {
				if (i - 1 < names.size()) {
					this.deleteCollectionName = names.get(i - 1);
					this.deleteCollectionOpen = true;
					this.modalFocus = -1;
					Theme.click(0.9F);
				}

				return true;
			}
		}

		for (int i = 0; i < this.collectionChips.size(); i++) {
			if (this.collectionChips.get(i).contains(mouseX, mouseY)) {
				this.activeCollection = i == 0 ? null : (i - 1 < names.size() ? names.get(i - 1) : null);
				this.sharedCode = null;
				Theme.click();
				this.scroll = 0.0F;
				this.layoutChips();
				this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
				this.refilter();
				return true;
			}
		}

		if (this.newCollectionChip.contains(mouseX, mouseY)) {
			Theme.click(1.1F);
			this.openNameInput(null);
			return true;
		}

		if (this.activeCollection != null && this.addPostsChip.contains(mouseX, mouseY)) {
			this.addingToCollection = this.activeCollection;
			this.page = Page.BROWSE;
			this.category = Category.ALL;
			this.setSearch("");
			Theme.click(1.1F);
			this.layoutChips();
			this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight + 20;
			this.scroll = 0.0F;
			this.refilter();
			return true;
		}

		if (this.activeCollection != null && this.generateCodeChip.contains(mouseX, mouseY)) {
			if (this.sharedCode != null) {
				this.copyToClipboard(this.sharedCode);
				this.codeCopiedAt = System.currentTimeMillis();
				this.layoutChips();
				Theme.click(1.2F);
			} else if (!this.sharingCode) {
				this.generateShareCode();
			}

			return true;
		}

		if (this.loadCodeChip.contains(mouseX, mouseY)) {
			this.loadCodeOpen = true;
			this.loadCodeInput = "";
			this.loadCodeStatus = "";
			this.modalFocus = -1;
			Theme.click(1.1F);
			return true;
		}

		return false;
	}

	private void copyToClipboard(String text) {
		try {
			Minecraft.getInstance().keyboardHandler.setClipboard(text);
		} catch (Throwable ignored) {
			// Clipboard is best-effort.
		}
	}

	// Pastes clipboard text into the code field, keeping only the first five letters/digits.
	private void pasteLoadCode() {
		String clip;

		try {
			clip = Minecraft.getInstance().keyboardHandler.getClipboard();
		} catch (Throwable e) {
			return;
		}

		for (int i = 0; i < clip.length() && this.loadCodeInput.length() < 5; i++) {
			char c = clip.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				this.loadCodeInput += Character.toUpperCase(c);
			}
		}
	}

	private void generateShareCode() {
		String collection = this.activeCollection;

		if (collection == null) {
			return;
		}

		List<String> ids = new ArrayList<>(CollectionStore.postIds(collection));

		if (ids.isEmpty()) {
			this.status = "Add some posts before sharing this collection.";
			return;
		}

		this.sharingCode = true;
		Theme.click(1.1F);

		Thread worker = new Thread(() -> {
			String code = Backend.shareCollection(collection, ids);
			Minecraft.getInstance().execute(() -> {
				this.sharingCode = false;

				if (code != null && collection.equals(this.activeCollection)) {
					this.sharedCode = code;
					this.layoutChips();
				} else if (code == null) {
					this.status = "Could not create a code, try again.";
				}
			});
		}, "schematicindex-share");
		worker.setDaemon(true);
		worker.start();
	}

	private void confirmLoadCode() {
		String code = this.loadCodeInput.trim().toUpperCase(Locale.ROOT);

		if (code.length() != 5) {
			this.loadCodeStatus = "Codes are 5 characters.";
			return;
		}

		this.loadCodeStatus = "Loading...";

		Thread worker = new Thread(() -> {
			com.google.gson.JsonObject data = Backend.loadCollectionCode(code);
			Minecraft.getInstance().execute(() -> {
				if (data == null) {
					this.loadCodeStatus = "Unknown code.";
					return;
				}

				List<String> ids = new ArrayList<>();

				for (com.google.gson.JsonElement element : data.getAsJsonArray("postIds")) {
					ids.add(element.getAsString());
				}

				if (ids.isEmpty()) {
					this.loadCodeStatus = "That collection is empty.";
					return;
				}

				// Keep the collection's original name; disambiguate if the name already exists.
				String original = data.has("name") && !data.get("name").isJsonNull()
						? data.get("name").getAsString().trim() : "";
				String name = original.isEmpty() ? "Shared " + code : original;

				if (CollectionStore.names().contains(name)) {
					name = name + " (" + code + ")";
				}

				CollectionStore.create(name);

				for (String id : ids) {
					if (!CollectionStore.contains(name, id)) {
						CollectionStore.toggle(name, id);
					}
				}

				this.loadCodeOpen = false;
				this.activeCollection = name;
				this.sharedCode = null;
				this.layoutChips();
				this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
				this.refilter();
				Toasts.push("Collection loaded", ids.size() + " posts added.", new ItemStack(Items.BOOKSHELF));
			});
		}, "schematicindex-loadcode");
		worker.setDaemon(true);
		worker.start();
	}

	private void openNameInput(@Nullable String pendingPost) {
		this.nameInputOpen = true;
		this.nameInput = "";
		this.namePendingPost = pendingPost;
		this.modalFocus = -1;
	}

	private void confirmNameInput() {
		String name = CollectionStore.normalize(this.nameInput);

		if (!name.isEmpty()) {
			CollectionStore.create(name);

			if (this.namePendingPost != null) {
				CollectionStore.toggle(name, this.namePendingPost);
			} else {
				this.activeCollection = name;
			}
		}

		this.nameInputOpen = false;
		this.namePendingPost = null;

		if (this.page == Page.SAVED) {
			this.layoutChips();
			this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
			this.refilter();
		}
	}

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
				SchematicEntry card = this.visible.get(index);
				this.renderCard(ctx, card, x, y, mouseX, mouseY);

				if (index == this.focusedCard) {
					// Match the hover outline exactly (flush to the card) so the top edge isn't
					// clipped by the grid scissor when the focused card is on the top row.
					Theme.roundedOutline(ctx, x, y, this.cardWidth, this.cardHeight,
							Theme.RADIUS_CARD, Theme.ACCENT_BRIGHT);
				}

				if (this.addingToCollection != null && CollectionStore.contains(this.addingToCollection, card.id())) {
					Theme.roundedOutline(ctx, x - 1, y - 1, this.cardWidth + 2, this.cardHeight + 2,
							Theme.RADIUS_CARD, Theme.ACCENT);
					int badge = this.font.width(Theme.bold("Added")) + 10;
					Theme.roundedRect(ctx, x + this.cardWidth - badge - 4, y + 4, badge, 12, Theme.RADIUS_PILL, Theme.ACCENT);
					Theme.text(ctx, this.font, Theme.bold("Added"), x + this.cardWidth - badge, y + 6, Theme.ON_ACCENT);
				}
			}
		}

		if (shown < this.visible.size()) {
			int rows = (shown + this.columns - 1) / this.columns;
			int y = this.gridTop + rows * rowHeight - Math.round(this.scroll) + 4;
			String more = "Loading more...";
			Theme.text(ctx, this.font, more, this.contentX + (this.contentWidth - this.font.width(more)) / 2, y,
					Theme.TEXT_ASH);
		}

		ctx.disableScissor();
	}

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

		// "New" badge (top-left) for posts uploaded since the last visit.
		if (newSinceCutoff > 0L && entry.postedAt() > newSinceCutoff && !entry.id().equals("preview")) {
			int badge = this.font.width(Theme.bold("New")) + 8;
			Theme.roundedRect(ctx, x + 3, y + 3, badge + 2, 13, Theme.RADIUS_PILL, 0xFF000000);
			Theme.roundedRect(ctx, x + 4, y + 4, badge, 11, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);
			Theme.text(ctx, this.font, Theme.bold("New"), x + 8, y + 6, Theme.ON_ACCENT);
		}

		int textX = x + 5;
		int textWidth = this.cardWidth - 10;

		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, entry.cardName(), textWidth)),
				textX, y + imageHeight + 6, Theme.TEXT);

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

	// A floating "back to top" button in the bottom-right, shown once a scrollable page has been
	// scrolled down a fair way.
	private void renderBackToTop(GuiGraphics ctx, int mouseX, int mouseY, boolean overlayOpen) {
		boolean dashboard = this.page == Page.UPLOAD && !this.uploadFormOpen;
		float active = dashboard ? this.dashScroll : this.scroll;
		boolean scrollable = this.page != Page.UPLOAD || dashboard;

		if (overlayOpen || this.tosOpen || this.tutorialActive || !scrollable || active < 140.0F) {
			this.backToTopButton.set(0, 0, 0, 0);
			return;
		}

		int size = 22;
		int rightEdge = this.page == Page.BROWSE && !RemoteContent.partners().isEmpty()
				? this.width - PARTNER_RAIL_WIDTH : this.contentX + this.contentWidth;
		int bx = rightEdge - size - 8;
		int by = this.height - size - 10;
		this.backToTopButton.set(bx, by, size, size);

		boolean hovered = this.backToTopButton.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, bx, by, size, size, size / 2, hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, bx, by, size, size, size / 2, hovered ? Theme.ACCENT : Theme.HAIRLINE);

		// Up chevron.
		int cx = bx + size / 2;
		int cy = by + size / 2;
		int color = hovered ? Theme.ACCENT_BRIGHT : Theme.TEXT;

		for (int i = 0; i < 4; i++) {
			ctx.fill(cx - i, cy - 3 + i, cx - i + 1, cy - 2 + i, color);
			ctx.fill(cx + i, cy - 3 + i, cx + i + 1, cy - 2 + i, color);
		}
	}

	// A read-only list of matching post titles under the search box, so you can tell whether
	// what you're looking for exists as you type. (Full server-side search comes later.)
	private void renderSearchSuggestions(GuiGraphics ctx) {
		if (!this.searchBox.isFocused() || this.detail != null) {
			return;
		}

		String q = this.query.trim().toLowerCase(Locale.ROOT);

		if (q.length() < 2) {
			return;
		}

		List<String> matches = new ArrayList<>();

		for (SchematicEntry entry : Catalogue.posts()) {
			String title = entry.title();

			if (title.toLowerCase(Locale.ROOT).contains(q) && !matches.contains(title)) {
				matches.add(title);

				if (matches.size() >= 6) {
					break;
				}
			}
		}

		if (matches.isEmpty()) {
			return;
		}

		int x = this.searchBox.getX() - 6;
		int w = this.searchBox.getWidth() + 12;
		int rowH = this.font.lineHeight + 5;
		int y = TOP_BAR_HEIGHT + 2;
		int h = matches.size() * rowH + 6;

		Theme.roundedRect(ctx, x, y, w, h, Theme.RADIUS_CARD, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x, y, w, h, Theme.RADIUS_CARD, Theme.HAIRLINE);

		int ty = y + 5;

		for (String match : matches) {
			Theme.text(ctx, this.font, Theme.clip(this.font, match, w - 12), x + 6, ty, Theme.TEXT_MUTE);
			ty += rowH;
		}
	}

	private void renderScrollbar(GuiGraphics ctx) {
		if (this.maxScroll <= 0.0F) {
			return;
		}

		int trackHeight = this.gridBottom - this.gridTop;
		int barHeight = Math.max(16, Math.round(trackHeight * (trackHeight / (trackHeight + this.maxScroll))));
		int barY = this.gridTop + Math.round((trackHeight - barHeight) * (this.scroll / this.maxScroll));
		int barX = Math.min(this.contentX + this.contentWidth + 2, this.width - 4);
		this.drawScrollThumb(ctx, SCROLLBAR_MAIN, barX, 3, barY, barHeight, 1, this.gridTop, trackHeight, Theme.HAIRLINE);
	}

	// Draws a scrollbar thumb and records its geometry so it can be grabbed and dragged. The
	// thumb brightens while it is being dragged.
	private void drawScrollThumb(GuiGraphics ctx, int channel, int barX, int barWidth, int barY, int barHeight,
			int radius, int trackTop, int trackHeight, int baseColor) {
		this.scrollbarChannel = channel;
		this.scrollbarX = barX;
		this.scrollbarWidth = barWidth;
		this.scrollbarThumbY = barY;
		this.scrollbarThumbHeight = barHeight;
		this.scrollbarTrackTop = trackTop;
		this.scrollbarTrackHeight = trackHeight;
		int color = this.draggingScrollbar && this.dragScrollChannel == channel ? lighten(baseColor) : baseColor;
		Theme.roundedRect(ctx, barX, barY, barWidth, barHeight, radius, color);
	}

	// A brighter, more opaque version of a scrollbar colour, shown while the thumb is held.
	private static int lighten(int argb) {
		int a = Math.min(255, ((argb >>> 24) & 0xFF) + 80);
		int r = Math.min(255, ((argb >> 16) & 0xFF) + 60);
		int g = Math.min(255, ((argb >> 8) & 0xFF) + 60);
		int b = Math.min(255, (argb & 0xFF) + 60);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private boolean scrollbarOverlayBlocked() {
		return this.detail != null || this.errorOpen || this.nameInputOpen || this.deleteCollectionOpen
				|| this.collectionOptionsOpen || this.renameCollectionOpen || this.tutorialActive
				|| this.designerWarnOpen || this.reportOpen || this.pendingOverwrite != null
				|| this.postOptionsOpen || this.editPostOpen || this.unpublishOpen || this.loadCodeOpen
				|| this.duplicateOpen;
	}

	// Begins a scrollbar drag if the click landed on (or near) the thumb of the bar drawn this
	// frame. A few pixels of horizontal slack make the thin bar easier to grab.
	private boolean tryGrabScrollbar(double mouseX, double mouseY) {
		if (this.scrollbarChannel == SCROLLBAR_NONE || this.scrollbarOverlayBlocked()) {
			return false;
		}

		boolean inTrackX = mouseX >= this.scrollbarX - 4 && mouseX <= this.scrollbarX + this.scrollbarWidth + 4;

		if (!inTrackX || mouseY < this.scrollbarTrackTop || mouseY > this.scrollbarTrackTop + this.scrollbarTrackHeight) {
			return false;
		}

		this.draggingScrollbar = true;
		this.dragScrollChannel = this.scrollbarChannel;

		if (mouseY >= this.scrollbarThumbY && mouseY <= this.scrollbarThumbY + this.scrollbarThumbHeight) {
			// Grabbed the thumb directly — keep the point under the cursor fixed while dragging.
			this.scrollbarGrabOffset = (float) mouseY - this.scrollbarThumbY;
		} else {
			// Clicked the track — jump the thumb centre to the cursor, then drag from there.
			this.scrollbarGrabOffset = this.scrollbarThumbHeight / 2.0F;
			this.dragScrollbarTo(mouseY);
		}

		Theme.click(0.8F);
		return true;
	}

	// Maps a cursor Y to a scroll value for the channel being dragged.
	private void dragScrollbarTo(double mouseY) {
		int travel = this.scrollbarTrackHeight - this.scrollbarThumbHeight;

		if (travel <= 0) {
			return;
		}

		float fraction = (float) ((mouseY - this.scrollbarGrabOffset - this.scrollbarTrackTop) / travel);
		fraction = Math.max(0.0F, Math.min(1.0F, fraction));

		switch (this.dragScrollChannel) {
			case SCROLLBAR_MAIN -> this.scroll = fraction * this.maxScroll;
			case SCROLLBAR_DASH -> this.dashScroll = fraction * this.dashMaxScroll;
			case SCROLLBAR_TOS -> {
				this.tosScroll = fraction * this.tosMaxScroll;

				if (this.tosScroll >= this.tosMaxScroll - 0.5F) {
					this.tosScrolledBottom = true;
				}
			}
			default -> {
			}
		}
	}

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

			return;
		}

		if (!this.uploadFormOpen) {
			this.renderUploadDashboard(ctx, mouseX, mouseY, partialTick);
			return;
		}

		int margin = 18;
		int areaX = this.contentX + margin;
		int areaW = this.contentWidth - margin * 2;
		int top = TOP_BAR_HEIGHT + 16;

		this.uploadBackButton.set(areaX, top - 4, 52, FIELD_HEIGHT);
		this.pillButton(ctx, this.uploadBackButton, "< Back", mouseX, mouseY, false);
		Theme.text(ctx, this.font, Theme.bold("New post"), areaX + 62, top, Theme.TEXT);
		this.signOutButton.set(areaX + areaW - 58, top - 4, 58, FIELD_HEIGHT);
		this.pillButton(ctx, this.signOutButton, "Sign out", mouseX, mouseY, false);

		int gap = 24;
		int leftW = Math.min(258, (areaW - gap) * 44 / 100);
		int leftX = areaX;
		int rightX = areaX + leftW + gap;
		int rightW = areaX + areaW - rightX;

		y = top + 26;

		Theme.text(ctx, this.font, "Schematic name", leftX, y, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, "on the post page",
				leftX + leftW - this.font.width("on the post page"), y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.titleBox, leftX, y, leftW, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 12;

		Theme.text(ctx, this.font, "Thumbnail name", leftX, y, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, "on the card", leftX + leftW - this.font.width("on the card"), y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.thumbnailBox, leftX, y, leftW, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 12;

		Theme.text(ctx, this.font, "Designed by", leftX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.designerBox, leftX, y, leftW, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 12;

		Theme.text(ctx, this.font, "Description", leftX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.field(ctx, this.descriptionBox, leftX, y, leftW, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 14;

		this.formCategoryButton.set(leftX, y, leftW, FIELD_HEIGHT);
		this.pillButton(ctx, this.formCategoryButton, "Category: " + this.formCategory.label(), mouseX, mouseY, false);
		y += FIELD_HEIGHT + 14;

		Theme.text(ctx, this.font, "Schematic file", leftX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.uploadSchematicButton.set(leftX, y, leftW, FIELD_HEIGHT);
		this.pillButton(ctx, this.uploadSchematicButton,
				this.formSchematic == null ? "Choose .litematic" : "Change file", mouseX, mouseY, false);
		y += FIELD_HEIGHT + 4;

		String chosen = this.formSchematic == null ? "No file chosen" : this.formSchematic.getFileName().toString();
		Theme.text(ctx, this.font, Theme.clip(this.font, chosen, leftW), leftX, y,
				this.formSchematic == null ? Theme.TEXT_ASH : Theme.ACCENT_BRIGHT);
		y += this.font.lineHeight + 14;

		String pictureCount = this.formPictures.isEmpty() ? "1-5 images" : this.formPictures.size() + " selected";
		Theme.text(ctx, this.font, "Pictures", leftX, y, Theme.TEXT_ASH);
		Theme.text(ctx, this.font, pictureCount, leftX + leftW - this.font.width(pictureCount), y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 3;
		this.uploadPicturesButton.set(leftX, y, leftW, FIELD_HEIGHT);
		this.pillButton(ctx, this.uploadPicturesButton, "Upload Pictures", mouseX, mouseY, false);
		y += FIELD_HEIGHT + 16;

		this.postButton.set(leftX, y, leftW, FIELD_HEIGHT);

		if (this.uploading) {
			this.uploadingButton(ctx, this.postButton);
		} else {
			this.pillButton(ctx, this.postButton, "Post", mouseX, mouseY, true);
		}

		y += FIELD_HEIGHT + 8;

		for (String line : this.wrap("Ctrl+V to paste a image or discord link to a schematic", leftW, 2)) {
			Theme.text(ctx, this.font, line, leftX, y, Theme.TEXT_MUTE);
			y += this.font.lineHeight + 2;
		}

		y += 5;

		if (this.uploading) {
			long elapsed = System.currentTimeMillis() - this.uploadStartedAt;

			if (this.uploadFileSize > 8L * 1024 * 1024 || elapsed > 10_000L) {
				for (String line : this.wrap("This is a large schematic file, uploading may take a while.", leftW, 2)) {
					Theme.text(ctx, this.font, line, leftX, y, Theme.TEXT_ASH);
					y += this.font.lineHeight + 1;
				}
			}
		} else if (!this.formStatus.isEmpty()) {
			Theme.text(ctx, this.font, Theme.clip(this.font, this.formStatus, leftW), leftX, y, Theme.ACCENT_BRIGHT);
		}

		SchematicEntry preview = this.previewEntry();
		int ry = top + 26;

		Theme.text(ctx, this.font, Theme.bold("Post Thumbnail"), rightX, ry, Theme.TEXT_MUTE);
		ry += this.font.lineHeight + 6;

		int cardPreviewWidth = Math.min(rightW, 138);
		int savedCardWidth = this.cardWidth;
		int savedCardHeight = this.cardHeight;
		this.cardWidth = cardPreviewWidth;
		this.cardHeight = imageHeight(cardPreviewWidth) + CAPTION_HEIGHT;
		// The card shows whichever picture the user picked as the thumbnail (image 0 by default).
		this.renderCard(ctx, this.thumbnailPreviewEntry(preview), rightX, ry, -999, -999);
		ry += this.cardHeight + 16;
		this.cardWidth = savedCardWidth;
		this.cardHeight = savedCardHeight;

		Theme.text(ctx, this.font, Theme.bold("In the post"), rightX, ry, Theme.TEXT_MUTE);
		ry += this.font.lineHeight + 6;
		this.renderDetailPreview(ctx, preview, rightX, ry, rightW, mouseX, mouseY);
	}

	// Like the preview, but with its first image pointed at the chosen thumbnail so the little
	// "Post Thumbnail" card shows the picture the user picked.
	private SchematicEntry thumbnailPreviewEntry(SchematicEntry preview) {
		if (this.formPictures.isEmpty() || this.formPictureStart < 0 || this.formThumbnailIndex <= 0) {
			return preview;
		}

		int start = this.formPictureStart + Math.min(this.formThumbnailIndex, this.formPictures.size() - 1);
		return SchematicEntry.local("preview", preview.title(), preview.thumbnailName(), preview.poster(),
				preview.designer(), preview.category(), preview.sizeX(), preview.sizeY(), preview.sizeZ(),
				preview.blockCount(), 0, 0, preview.postedAt(), preview.description(), preview.imageCount(),
				start, -1, false);
	}

	private SchematicEntry previewEntry() {
		String title = this.titleBox.getValue().trim();

		if (title.isEmpty()) {
			title = "Your build";
		}

		String poster = UploaderAccess.profile() == null ? "you" : UploaderAccess.profile();
		String designer = this.designerBox.getValue().trim();
		return SchematicEntry.local("preview", title, this.thumbnailBox.getValue().trim(), poster,
				designer.isEmpty() ? "Unknown" : designer, this.formCategory,
				this.formSizeX, this.formSizeY, this.formSizeZ, this.formBlockCount, 0, 0,
				System.currentTimeMillis(), this.descriptionBox.getValue().trim(),
				this.formPictures.size(), this.formPictureStart, -1, false);
	}

	private void renderDetailPreview(GuiGraphics ctx, SchematicEntry entry, int x, int y, int modalWidth, int mouseX, int mouseY) {
		int pad = 12;
		int imageWidth = Math.round((modalWidth - pad * 3) * 0.56F);
		int imageHeight = imageHeight(imageWidth);

		List<String> descLines = this.wrap(entry.description(), modalWidth - pad * 2, 2);
		int modalHeight = pad + imageHeight + 6 + descLines.size() * (this.font.lineHeight + 1) + 10 + 16 + pad;

		Theme.roundedRect(ctx, x, y, modalWidth, modalHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);

		Identifier texture = entry.imageCount() > 0 ? this.imageTexture(entry, this.formPicturePreview) : null;

		if (texture != null) {
			Theme.image(ctx, texture, x + pad, y + pad, imageWidth, imageHeight);
		} else {
			Theme.blueprintPlaceholder(ctx, x + pad, y + pad, imageWidth, imageHeight);
			String empty = "No pictures yet";
			Theme.text(ctx, this.font, empty, x + pad + (imageWidth - this.font.width(empty)) / 2, y + pad + imageHeight / 2 - 4, Theme.TEXT_ASH);
		}

		if (entry.imageCount() > 1) {
			this.formImagePrev.set(x + pad + 2, y + pad + imageHeight / 2 - 8, 12, 16);
			this.formImageNext.set(x + pad + imageWidth - 14, y + pad + imageHeight / 2 - 8, 12, 16);
			this.arrowButton(ctx, this.formImagePrev, true, mouseX, mouseY);
			this.arrowButton(ctx, this.formImageNext, false, mouseX, mouseY);

			String counter = (this.formPicturePreview + 1) + "/" + entry.imageCount();
			int cw = this.font.width(counter) + 8;
			Theme.roundedRect(ctx, x + pad + imageWidth - cw - 3, y + pad + imageHeight - 14, cw, 11, Theme.RADIUS_PILL, 0xCC0F1114);
			Theme.text(ctx, this.font, counter, x + pad + imageWidth - cw + 1, y + pad + imageHeight - 12, Theme.TEXT);
		} else {
			this.formImagePrev.set(0, 0, 0, 0);
			this.formImageNext.set(0, 0, 0, 0);
		}

		if (entry.imageCount() > 0) {
			int xs = 14;
			int rx = x + pad + imageWidth - xs - 4;
			int ry = y + pad + 4;
			this.formImageRemove.set(rx, ry, xs, xs);
			boolean removeHover = this.formImageRemove.contains(mouseX, mouseY);
			Theme.roundedRect(ctx, rx, ry, xs, xs, Theme.RADIUS_CARD, removeHover ? 0xEED64545 : 0xCC0F1114);
			Theme.roundedOutline(ctx, rx, ry, xs, xs, Theme.RADIUS_CARD, Theme.HAIRLINE);
			this.drawLine(ctx, rx + 4, ry + 4, rx + xs - 4, ry + xs - 4, Theme.TEXT);
			this.drawLine(ctx, rx + 4, ry + xs - 4, rx + xs - 4, ry + 4, Theme.TEXT);

			// "Set as thumbnail" for the currently shown picture.
			boolean isThumb = this.formPicturePreview == this.formThumbnailIndex;
			String label = isThumb ? "★ Thumbnail" : "Set as thumbnail";
			int tw = this.font.width(label) + 10;
			int tx = x + pad + 4;
			int ty = y + pad + imageHeight - 15;
			this.formThumbnailButton.set(tx, ty, tw, 12);
			boolean thumbHover = this.formThumbnailButton.contains(mouseX, mouseY);
			Theme.roundedRect(ctx, tx, ty, tw, 12, Theme.RADIUS_PILL,
					isThumb ? Theme.ACCENT : (thumbHover ? 0xEE000000 : 0xCC0F1114));
			Theme.text(ctx, this.font, label, tx + 5, ty + 2, isThumb ? Theme.ON_ACCENT : Theme.TEXT);
		} else {
			this.formImageRemove.set(0, 0, 0, 0);
			this.formThumbnailButton.set(0, 0, 0, 0);
		}

		int infoX = x + pad + imageWidth + pad;
		int infoWidth = modalWidth - (infoX - x) - pad;
		int line = y + pad;

		String age = entry.agoLabel();
		int ageWidth = this.font.width(age);
		Theme.text(ctx, this.font, age, x + modalWidth - pad - ageWidth, y + pad, Theme.TEXT_ASH);

		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, entry.title(), infoWidth - ageWidth - 6)), infoX, line, Theme.TEXT);
		line += this.font.lineHeight + 4;

		String followLabel = "Follow";
		int followWidth = this.font.width(Theme.bold(followLabel)) + 12;
		Rect follow = new Rect();
		follow.set(infoX + infoWidth - followWidth, line - 1, followWidth, 12);
		this.pillButton(ctx, follow, followLabel, mouseX, mouseY, false);

		Theme.text(ctx, this.font, Theme.clip(this.font, "Posted by " + entry.poster(), infoWidth - followWidth - 6), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 3;

		String designer = entry.designer().isBlank() ? "Unknown" : entry.designer();
		Theme.text(ctx, this.font, Theme.clip(this.font, "Designed by " + designer, infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 6;

		line = this.metaRow(ctx, "Dimensions", entry.dimensionsLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Total Blocks", entry.blockCountLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Volume", entry.volumeLabel(), infoX, line, infoWidth);

		Theme.text(ctx, this.font, "Downloads", infoX, line, Theme.TEXT_ASH);
		String downloads = entry.downloadsLabel();
		int downloadsWidth = this.font.width(downloads);
		Theme.text(ctx, this.font, downloads, infoX + infoWidth - downloadsWidth, line, Theme.TEXT);
		Theme.downloadGlyph(ctx, infoX + infoWidth - downloadsWidth - Theme.DOWNLOAD_GLYPH_WIDTH - 3, line + 2, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 2;

		Theme.text(ctx, this.font, "Likes", infoX, line, Theme.TEXT_ASH);
		String likeCount = SchematicEntry.compact(entry.likes());
		int likeWidth = this.font.width(likeCount);
		Theme.text(ctx, this.font, likeCount, infoX + infoWidth - likeWidth, line, Theme.TEXT);
		Theme.heart(ctx, infoX + infoWidth - likeWidth - HEART_SIZE - 4, line - 1, false);

		int descriptionY = y + pad + imageHeight + 6;

		for (String row : descLines) {
			Theme.text(ctx, this.font, row, x + pad, descriptionY, Theme.TEXT_MUTE);
			descriptionY += this.font.lineHeight + 1;
		}

		int buttonY = y + modalHeight - pad - 16;
		int downloadW = this.font.width(Theme.bold("Download")) + 18;
		int previewW = this.font.width(Theme.bold("3D preview")) + 18;
		int closeW = this.font.width(Theme.bold("Close")) + 18;
		int saveW = this.font.width(Theme.bold("Save for later")) + 18;

		Rect close = new Rect();
		close.set(x + pad, buttonY, closeW, 16);
		Rect save = new Rect();
		save.set(close.x + closeW + 6, buttonY, saveW, 16);
		Rect download = new Rect();
		download.set(x + modalWidth - pad - downloadW, buttonY, downloadW, 16);
		Rect preview3d = new Rect();
		preview3d.set(download.x - 6 - previewW, buttonY, previewW, 16);

		this.pillButton(ctx, close, "Close", mouseX, mouseY, false);
		this.pillButton(ctx, save, "Save for later", mouseX, mouseY, false);
		this.pillButton(ctx, preview3d, "3D preview", mouseX, mouseY, false);
		this.pillButton(ctx, download, "Download", mouseX, mouseY, true);
	}

	private void renderUploadDashboard(GuiGraphics ctx, int mouseX, int mouseY, float partialTick) {
		if (!this.myStatsLoaded && !this.myStatsLoading) {
			this.loadMyStats();
		}

		int margin = 18;
		int areaX = this.contentX + margin;
		int areaW = this.contentWidth - margin * 2;
		int top = TOP_BAR_HEIGHT + 16;

		String signedIn = "Signed in as " + UploaderAccess.profile();
		Theme.text(ctx, this.font, Theme.bold(signedIn), areaX, top, Theme.TEXT);

		String uploaderIgn = UploaderAccess.ign();

		if (uploaderIgn != null && !uploaderIgn.isBlank()) {
			int headX = areaX + this.font.width(Theme.bold(signedIn)) + 6;
			int headY = top - 3;
			Identifier head = ImageStore.avatar("https://mc-heads.net/avatar/" + Backend.encode(uploaderIgn) + "/64.png");

			if (head != null) {
				Theme.image(ctx, head, headX, headY, 14, 14, 64, 64);
			}
		}

		this.signOutButton.set(areaX + areaW - 58, top - 4, 58, FIELD_HEIGHT);
		this.pillButton(ctx, this.signOutButton, "Sign out", mouseX, mouseY, false);
		this.dashUploadButton.set(areaX + areaW - 58 - 6 - 86, top - 4, 86, FIELD_HEIGHT);
		this.pillButton(ctx, this.dashUploadButton, "+ New post", mouseX, mouseY, true);

		if (this.myStatsLoading || !this.myStatsOk) {
			this.renderDashboardSkeleton(ctx, areaX, top + 26, areaW);
			return;
		}

		// The whole page scrolls as one column: stats and graph at the top, then every post
		// below at full size. Only the sign-in row above stays fixed.
		int scrollTop = top + 26;
		int scrollBottom = this.height - 6;
		this.dashViewportTop = scrollTop;
		this.dashViewportBottom = scrollBottom;

		int tileH = 42;
		int graphH = 150;
		int gap = GUTTER;
		int cols = Math.max(1, (areaW + gap) / (112 + gap));
		int cw = (areaW - gap * (cols - 1)) / cols;
		int ch = imageHeight(cw) + CAPTION_HEIGHT;

		List<SchematicEntry> shown = new ArrayList<>();

		for (SchematicEntry entry : this.myPosts) {
			if (this.myFilter == Category.ALL || entry.category() == this.myFilter) {
				shown.add(entry);
			}
		}

		int uploadsHeaderH = this.font.lineHeight + 6;
		int chipsH = this.measureFilterChipsHeight(areaX, areaW);
		int postsH = shown.isEmpty()
				? this.font.lineHeight
				: ((shown.size() + cols - 1) / cols) * (ch + gap) - gap;

		int contentHeight = tileH + 14 + graphH + 14 + uploadsHeaderH + chipsH + 6 + postsH;
		this.dashMaxScroll = Math.max(0.0F, contentHeight - (scrollBottom - scrollTop));
		this.dashScroll = Math.max(0.0F, Math.min(this.dashScroll, this.dashMaxScroll));

		ctx.enableScissor(this.contentX, scrollTop, this.contentX + this.contentWidth, scrollBottom);

		int y = scrollTop - Math.round(this.dashScroll);

		String[][] tiles = {
			{"Posts", SchematicEntry.compact(this.myPostsCount)},
			{"Views", SchematicEntry.compact(this.myViews)},
			{"Downloads", SchematicEntry.compact(this.myDownloads)},
			{"Likes", SchematicEntry.compact(this.myLikes)},
			{"Avg Stars", this.myRatingCount > 0 ? String.format(Locale.ROOT, "%.1f", this.myRating) : "-"},
			{"Followers", SchematicEntry.compact(this.myFollowers)},
		};
		int tileGap = 8;
		int tileW = (areaW - tileGap * (tiles.length - 1)) / tiles.length;

		for (int i = 0; i < tiles.length; i++) {
			int tx = areaX + i * (tileW + tileGap);
			Theme.roundedRect(ctx, tx, y, tileW, tileH, Theme.RADIUS_CARD, Theme.SURFACE_CARD);
			Theme.text(ctx, this.font, tiles[i][0], tx + 8, y + 8, Theme.TEXT_MUTE);
			Theme.textScaled(ctx, this.font, Theme.bold(tiles[i][1]), tx + 8, y + 19, 1.5F, Theme.TEXT);
		}

		y += tileH + 14;

		this.renderLineGraph(ctx, areaX, y, areaW, graphH, mouseX, mouseY);
		y += graphH + 14;

		Theme.text(ctx, this.font, Theme.bold("Click your posts to view it's stats on the graph"), areaX, y,
				Theme.TEXT_MUTE);
		y += this.font.lineHeight + 6;

		y = this.renderMyFilterChips(ctx, areaX, y, areaW, mouseX, mouseY) + 6;

		this.myPostCardRects.clear();
		this.myPostCardIds.clear();
		this.myPostEditRects.clear();

		if (shown.isEmpty()) {
			String msg = this.myPosts.isEmpty()
					? "You haven't uploaded anything yet. Hit New post to start."
					: "No posts in this category.";
			Theme.text(ctx, this.font, Theme.clip(this.font, msg, areaW), areaX, y, Theme.TEXT_ASH);
		} else {
			int savedW = this.cardWidth;
			int savedH = this.cardHeight;
			this.cardWidth = cw;
			this.cardHeight = ch;

			for (int i = 0; i < shown.size(); i++) {
				int col = i % cols;
				int row = i / cols;
				int cx = areaX + col * (cw + gap);
				int cy = y + row * (ch + gap);

				Rect rect = new Rect();
				rect.set(cx, cy, cw, ch);
				this.myPostCardRects.add(rect);
				this.myPostCardIds.add(shown.get(i).id());

				Rect editRect = new Rect();
				editRect.set(cx + cw - 16, cy + 3, 13, 13);
				this.myPostEditRects.add(editRect);

				if (cy + ch >= scrollTop && cy <= scrollBottom) {
					this.renderCard(ctx, shown.get(i), cx, cy, -999, -999);

					boolean selected = shown.get(i).id().equals(this.selectedDashPost);
					boolean hovered = rect.contains(mouseX, mouseY)
							&& mouseY >= scrollTop && mouseY <= scrollBottom;

					if (selected) {
						Theme.roundedOutline(ctx, cx, cy, cw, ch, Theme.RADIUS_CARD, Theme.ACCENT_BRIGHT);
					} else if (hovered) {
						Theme.roundedOutline(ctx, cx, cy, cw, ch, Theme.RADIUS_CARD, Theme.HAIRLINE);
					}

					// A small edit button in the top-right that opens the edit/unpublish options.
					boolean editHover = editRect.contains(mouseX, mouseY)
							&& mouseY >= scrollTop && mouseY <= scrollBottom;
					Theme.roundedRect(ctx, editRect.x, editRect.y, editRect.width, editRect.height,
							Theme.RADIUS_PILL, editHover ? 0xF0000000 : 0xB0000000);
					Theme.editGlyph(ctx, editRect.x + 3, editRect.y + 3,
							editHover ? Theme.ACCENT_BRIGHT : Theme.TEXT);
				}
			}

			this.cardWidth = savedW;
			this.cardHeight = savedH;
		}

		ctx.disableScissor();

		if (this.dashMaxScroll > 0.0F) {
			int trackH = scrollBottom - scrollTop;
			int barH = Math.max(16, Math.round(trackH * (trackH / (trackH + this.dashMaxScroll))));
			int barY = scrollTop + Math.round((trackH - barH) * (this.dashScroll / this.dashMaxScroll));
			int barX = Math.min(areaX + areaW + 2, this.width - 4);
			this.drawScrollThumb(ctx, SCROLLBAR_DASH, barX, 3, barY, barH, 1, scrollTop, trackH, Theme.HAIRLINE);
		}
	}

	// Height the upload filter chips will occupy at the given width (mirrors the wrap in
	// renderMyFilterChips), used to lay out the scrolling dashboard before drawing.
	private int measureFilterChipsHeight(int x, int w) {
		int cx = x;
		int rows = 0;

		for (Category value : Category.values()) {
			int chipW = this.font.width(Theme.bold(value.label())) + 12;

			if (cx + chipW > x + w) {
				cx = x;
				rows++;
			}

			cx += chipW + CHIP_GAP;
		}

		return (rows + 1) * CHIP_HEIGHT + rows * CHIP_GAP;
	}

	private int renderMyFilterChips(GuiGraphics ctx, int x, int y, int w, int mouseX, int mouseY) {
		Category[] all = Category.values();

		while (this.myFilterChips.size() < all.length) {
			this.myFilterChips.add(new Rect());
		}

		int cx = x;
		int cy = y;

		for (int i = 0; i < all.length; i++) {
			Category value = all[i];
			String label = Theme.bold(value.label());
			int chipW = this.font.width(label) + 12;

			if (cx + chipW > x + w) {
				cx = x;
				cy += CHIP_HEIGHT + CHIP_GAP;
			}

			Rect rect = this.myFilterChips.get(i);
			rect.set(cx, cy, chipW, CHIP_HEIGHT);
			boolean active = value == this.myFilter;
			boolean hovered = rect.contains(mouseX, mouseY);
			int fill = active ? Theme.ACCENT : (hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
			Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);
			Theme.text(ctx, this.font, label, rect.x + 6, rect.y + (CHIP_HEIGHT - this.font.lineHeight) / 2 + 1,
					active ? Theme.ON_ACCENT : Theme.TEXT_MUTE);

			cx += chipW + CHIP_GAP;
		}

		return cy + CHIP_HEIGHT;
	}

	private void renderLineGraph(GuiGraphics ctx, int x, int y, int w, int h, int mouseX, int mouseY) {
		Theme.roundedRect(ctx, x, y, w, h, Theme.RADIUS_CARD, Theme.SURFACE_CARD);

		int legendChipH = this.font.lineHeight + 4;
		int legendY = y + 6;
		int legendToPlot = 11;
		int plotToLabel = 8;
		int labelH = this.font.lineHeight;
		int bottomGap = 5;

		int plotX = x + 32;
		int plotY = legendY + legendChipH + legendToPlot;
		int plotRight = x + w - 12;
		int plotBottom = y + h - bottomGap - labelH - plotToLabel;
		int plotW = plotRight - plotX;
		int plotH = plotBottom - plotY;

		int lx = x + 10;
		lx += this.legendChip(ctx, this.legendViewsRect, lx, legendY, "Views", Theme.STAT_VIEWS, this.graphMode == 1, mouseX, mouseY);
		lx += this.legendChip(ctx, this.legendDownloadsRect, lx, legendY, "Downloads", Theme.STAT_DOWNLOADS, this.graphMode == 2, mouseX, mouseY);
		lx += this.legendChip(ctx, this.legendLikesRect, lx, legendY, "Likes", Theme.STAT_LIKES, this.graphMode == 3, mouseX, mouseY);
		this.legendChip(ctx, this.legendStarsRect, lx, legendY, "Stars", Theme.STAT_STARS, this.graphMode == 4, mouseX, mouseY);

		if (this.myDays.length < 2 || plotH < 12) {
			String msg = "No activity yet.";
			Theme.text(ctx, this.font, msg, x + (w - this.font.width(msg)) / 2, plotY + plotH / 2 - 4, Theme.TEXT_ASH);
			return;
		}

		boolean showViews = this.graphMode == 0 || this.graphMode == 1;
		boolean showDownloads = this.graphMode == 0 || this.graphMode == 2;
		boolean showLikes = this.graphMode == 0 || this.graphMode == 3;
		boolean showStars = this.graphMode == 0 || this.graphMode == 4;

		// When a post is selected on the dashboard, graph its history instead of the totals.
		int[][] selected = this.selectedDashPost != null ? this.myPostSeries.get(this.selectedDashPost) : null;
		int[] viewSeries = selected != null ? selected[0] : this.myViewSeries;
		int[] downloadSeries = selected != null ? selected[1] : this.myDownloadSeries;
		int[] likeSeries = selected != null ? selected[2] : this.myLikeSeries;
		int[] starSeries = selected != null && selected.length > 3 ? selected[3] : this.myStarSeries;

		int rawMax = 1;
		if (showViews) {
			for (int v : viewSeries) rawMax = Math.max(rawMax, v);
		}
		if (showDownloads) {
			for (int v : downloadSeries) rawMax = Math.max(rawMax, v);
		}
		if (showLikes) {
			for (int v : likeSeries) rawMax = Math.max(rawMax, v);
		}
		if (showStars) {
			for (int v : starSeries) rawMax = Math.max(rawMax, v);
		}
		int max = niceCeil(rawMax);

		for (int g = 0; g <= 2; g++) {
			int value = max * g / 2;
			int gy = plotBottom - (int) Math.round((double) value / max * plotH);
			ctx.fill(plotX, gy, plotRight, gy + 1, g == 0 ? Theme.HAIRLINE : 0x18FFFFFF);
			String lbl = SchematicEntry.compact(value);
			Theme.text(ctx, this.font, lbl, plotX - 5 - this.font.width(lbl), gy - 3, Theme.TEXT_ASH);
		}

		float animT = this.graphAnim();

		if (showViews) {
			this.plotSeries(ctx, viewSeries, max, plotX, plotY, plotW, plotH, Theme.STAT_VIEWS, animT);
		}
		if (showDownloads) {
			this.plotSeries(ctx, downloadSeries, max, plotX, plotY, plotW, plotH, Theme.STAT_DOWNLOADS, animT);
		}
		if (showLikes) {
			this.plotSeries(ctx, likeSeries, max, plotX, plotY, plotW, plotH, Theme.STAT_LIKES, animT);
		}
		if (showStars) {
			this.plotSeries(ctx, starSeries, max, plotX, plotY, plotW, plotH, Theme.STAT_STARS, animT);
		}

		Theme.text(ctx, this.font, shortDay(this.myDays[0]), plotX, plotBottom + plotToLabel, Theme.TEXT_ASH);
		String lastLabel = shortDay(this.myDays[this.myDays.length - 1]);
		Theme.text(ctx, this.font, lastLabel, plotRight - this.font.width(lastLabel), plotBottom + plotToLabel, Theme.TEXT_ASH);

		if (mouseX >= plotX - 4 && mouseX <= plotRight + 4 && mouseY >= plotY - 6 && mouseY <= plotBottom + 6) {
			int n = this.myDays.length;
			int hi = Math.max(0, Math.min(n - 1, (int) Math.round((double) (mouseX - plotX) / plotW * (n - 1))));
			int hx = plotX + (int) Math.round((double) hi / (n - 1) * plotW);

			ctx.fill(hx, plotY, hx + 1, plotBottom, 0x33FFFFFF);

			List<String> rows = new ArrayList<>();
			List<Integer> rowColors = new ArrayList<>();

			if (showViews && hi < viewSeries.length) {
				int py = this.plotValueY(viewSeries[hi], max, plotY, plotH, animT);
				this.graphDot(ctx, hx, py, Theme.STAT_VIEWS);
				rows.add("Views: " + SchematicEntry.compact(viewSeries[hi]));
				rowColors.add(Theme.STAT_VIEWS);
			}
			if (showDownloads && hi < downloadSeries.length) {
				int py = this.plotValueY(downloadSeries[hi], max, plotY, plotH, animT);
				this.graphDot(ctx, hx, py, Theme.STAT_DOWNLOADS);
				rows.add("Downloads: " + SchematicEntry.compact(downloadSeries[hi]));
				rowColors.add(Theme.STAT_DOWNLOADS);
			}
			if (showLikes && hi < likeSeries.length) {
				int py = this.plotValueY(likeSeries[hi], max, plotY, plotH, animT);
				this.graphDot(ctx, hx, py, Theme.STAT_LIKES);
				rows.add("Likes: " + SchematicEntry.compact(likeSeries[hi]));
				rowColors.add(Theme.STAT_LIKES);
			}
			if (showStars && hi < starSeries.length) {
				int py = this.plotValueY(starSeries[hi], max, plotY, plotH, animT);
				this.graphDot(ctx, hx, py, Theme.STAT_STARS);
				rows.add("Stars: " + SchematicEntry.compact(starSeries[hi]));
				rowColors.add(Theme.STAT_STARS);
			}

			String date = fullDay(this.myDays[hi]);
			int pad = 6;
			int rowH = this.font.lineHeight + 2;
			int textWidth = this.font.width(Theme.bold(date));

			for (String row : rows) {
				textWidth = Math.max(textWidth, 10 + this.font.width(row));
			}

			int boxW = textWidth + pad * 2;
			int boxH = pad + this.font.lineHeight + 4 + rows.size() * rowH + pad - 2;
			int boxX = hx + 10 + boxW > x + w ? hx - 10 - boxW : hx + 10;
			boxX = Math.max(x + 2, Math.min(boxX, x + w - boxW - 2));
			int boxY = Math.max(y + 2, Math.min(mouseY - boxH / 2, y + h - boxH - 2));

			Theme.roundedRect(ctx, boxX, boxY, boxW, boxH, Theme.RADIUS_CARD, Theme.SURFACE_ELEVATED);
			Theme.roundedOutline(ctx, boxX, boxY, boxW, boxH, Theme.RADIUS_CARD, Theme.HAIRLINE);

			int ty = boxY + pad;
			Theme.text(ctx, this.font, Theme.bold(date), boxX + pad, ty, Theme.TEXT);
			ty += this.font.lineHeight + 4;

			for (int i = 0; i < rows.size(); i++) {
				ctx.fill(boxX + pad, ty + 2, boxX + pad + 4, ty + 6, rowColors.get(i));
				Theme.text(ctx, this.font, rows.get(i), boxX + pad + 10, ty, Theme.TEXT_MUTE);
				ty += rowH;
			}
		}
	}

	private void graphDot(GuiGraphics ctx, int centreX, int centreY, int color) {
		Theme.roundedRect(ctx, centreX - 3, centreY - 3, 6, 6, 3, 0xFFFFFFFF);
		Theme.roundedRect(ctx, centreX - 2, centreY - 2, 4, 4, 2, color);
	}

	private static String fullDay(String iso) {
		try {
			String[] parts = iso.split("-");
			String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
			int month = Math.max(1, Math.min(12, Integer.parseInt(parts[1])));
			return months[month - 1] + " " + Integer.parseInt(parts[2]) + ", " + parts[0];
		} catch (Exception e) {
			return iso;
		}
	}

	private int legendChip(GuiGraphics ctx, Rect rect, int x, int y, String label, int color, boolean active, int mouseX, int mouseY) {
		int chipW = 13 + this.font.width(label) + 8;
		int chipH = this.font.lineHeight + 4;
		rect.set(x, y, chipW, chipH);
		boolean hovered = rect.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, x, y, chipW, chipH, Theme.RADIUS_PILL, active || hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, chipW, chipH, Theme.RADIUS_PILL, active ? Theme.ACCENT_BRIGHT : Theme.HAIRLINE);
		ctx.fill(x + 6, y + chipH / 2 - 2, x + 10, y + chipH / 2 + 2, color);
		Theme.text(ctx, this.font, label, x + 13, y + (chipH - this.font.lineHeight) / 2 + 1, active ? Theme.TEXT : Theme.TEXT_MUTE);
		return chipW + 6;
	}

	private float graphAnim() {
		float t = Math.max(0.0F, Math.min(1.0F, (System.currentTimeMillis() - this.graphAnimStart) / 350.0F));
		return 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
	}

	private static int niceCeil(int value) {
		if (value <= 4) {
			return Math.max(1, value);
		}

		int pow = 1;

		while (pow * 10 < value) {
			pow *= 10;
		}

		for (int m : new int[]{1, 2, 5, 10}) {
			if (m * pow >= value) {
				return m * pow;
			}
		}

		return value;
	}

	private void plotSeries(GuiGraphics ctx, int[] series, int max, int plotX, int plotY, int plotW, int plotH, int color, float animT) {
		if (series.length < 2) {
			return;
		}

		int n = series.length;
		int prevX = 0;
		int prevY = 0;

		for (int i = 0; i < n; i++) {
			int px = plotX + (int) Math.round((double) i / (n - 1) * plotW);
			int py = this.plotValueY(series[i], max, plotY, plotH, animT);

			if (i > 0) {
				this.drawLine(ctx, prevX, prevY, px, py, color);
			}

			prevX = px;
			prevY = py;
		}
	}

	// Maps a series value to a plot y. A 2px floor keeps a zero value as a visible flat line
	// just above the axis instead of merging into it, so a series reads as flat-at-zero across
	// the whole time frame rather than only appearing once its first non-zero day arrives.
	private int plotValueY(int value, int max, int plotY, int plotH, float animT) {
		int usableH = Math.max(1, plotH - 2);
		return plotY + plotH - 2 - (int) Math.round((double) value / max * usableH * animT);
	}

	private void renderDashboardSkeleton(GuiGraphics ctx, int x, int y, int w) {
		int tileGap = 8;
		int tileW = (w - tileGap * 5) / 6;
		int tileH = 42;

		for (int i = 0; i < 6; i++) {
			this.skeletonRect(ctx, x + i * (tileW + tileGap), y, tileW, tileH);
		}

		int gy = y + tileH + 14;
		this.skeletonRect(ctx, x, gy, w, 140);
		gy += 140 + 14;
		this.skeletonRect(ctx, x, gy, 64, this.font.lineHeight + 2);
		gy += this.font.lineHeight + 10;

		int gap = GUTTER;
		int cols = Math.max(1, (w + gap) / (112 + gap));
		int cw = (w - gap * (cols - 1)) / cols;
		int ch = imageHeight(cw) + CAPTION_HEIGHT;
		int bottom = this.height - 6;

		while (gy + ch <= bottom) {
			for (int col = 0; col < cols; col++) {
				this.skeletonRect(ctx, x + col * (cw + gap), gy, cw, ch);
			}

			gy += ch + gap;
		}
	}

	private void skeletonRect(GuiGraphics ctx, int x, int y, int w, int h) {
		Theme.roundedRect(ctx, x, y, w, h, Theme.RADIUS_CARD, Theme.SKELETON);

		int period = 1400;
		float progress = (System.currentTimeMillis() % period) / (float) period;
		int shineX = x - 20 + Math.round(progress * (w + 40));

		ctx.enableScissor(x, y, x + w, y + h);

		for (int i = 0; i < 14; i++) {
			ctx.fill(shineX + i, y, shineX + i + 1, y + h, Theme.SKELETON_SHINE);
		}

		ctx.disableScissor();
	}

	private void drawLine(GuiGraphics ctx, int x0, int y0, int x1, int y1, int color) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float length = (float) Math.sqrt(dx * dx + dy * dy);

		if (length < 0.5F) {
			ctx.fill(x0, y0 - 1, x0 + 1, y0 + 1, color);
			return;
		}

		ctx.pose().pushMatrix();
		ctx.pose().translate((float) x0, (float) y0);
		ctx.pose().rotate((float) Math.atan2(dy, dx));
		ctx.fill(0, -1, Math.round(length) + 1, 1, color);
		ctx.pose().popMatrix();
	}

	private static String shortDay(String iso) {
		return iso.length() >= 10 ? iso.substring(5).replace('-', '/') : iso;
	}

	// Polls for new follows/likes on the signed-in uploader's posts and toasts the fresh ones.
	private void pollNotifications() {
		String code = UploaderAccess.code();

		if (code == null) {
			return;
		}

		Thread worker = new Thread(() -> {
			JsonObject data = Backend.notifications(code);

			if (data == null || !data.has("items") || !data.get("items").isJsonArray()) {
				return;
			}

			long seen = Settings.notificationsSeenAt();
			List<JsonObject> fresh = new ArrayList<>();
			long newest = seen;

			for (JsonElement el : data.getAsJsonArray("items")) {
				JsonObject item = el.getAsJsonObject();
				long at = item.has("at") ? item.get("at").getAsLong() : 0L;

				if (at > seen) {
					fresh.add(item);
					newest = Math.max(newest, at);
				}
			}

			long marked = newest;
			Minecraft.getInstance().execute(() -> {
				// Skip the historical backlog on the first poll (seen == 0): just mark it seen.
				if (seen != 0L) {
					int shown = 0;

					for (JsonObject item : fresh) {
						if (shown++ >= 5) {
							break;
						}

						String type = item.has("type") ? item.get("type").getAsString() : "";

						if (type.equals("follow")) {
							Toasts.push("New follower", "Someone followed you.", new ItemStack(Items.PLAYER_HEAD));
						} else if (type.equals("like")) {
							String title = item.has("postTitle") && !item.get("postTitle").isJsonNull()
									? item.get("postTitle").getAsString() : "your post";
							Toasts.push("New like", "Someone liked " + title + ".", new ItemStack(Items.POPPY));
						}
					}
				}

				Settings.setNotificationsSeenAt(marked);
			});
		}, "schematicindex-notif");
		worker.setDaemon(true);
		worker.start();
	}

	private void loadMyStats() {
		String code = UploaderAccess.code();

		if (code == null) {
			return;
		}

		this.myStatsLoading = true;
		this.myStatsOk = false;
		new Thread(() -> {
			// Start the graph at the launch date rather than a rolling 30-day window,
			// so it doesn't show flat-zero days from before there was any activity.
			int days;

			try {
				long span = java.time.temporal.ChronoUnit.DAYS.between(
						java.time.LocalDate.of(2026, 8, 10), java.time.LocalDate.now()) + 1;
				days = (int) Math.max(7, Math.min(365, span));
			} catch (Throwable e) {
				days = 30;
			}

			JsonObject data = Backend.myStats(code, days);
			Minecraft.getInstance().execute(() -> {
				this.myStatsLoading = false;
				this.myStatsLoaded = true;

				if (data != null) {
					this.applyMyStats(data);
					this.myStatsOk = true;
					this.graphAnimStart = System.currentTimeMillis();
				}
			});
		}, "schematicindex-mystats").start();
	}

	private void applyMyStats(JsonObject data) {
		try {
			JsonObject totals = data.getAsJsonObject("totals");
			this.myPostsCount = totals.get("posts").getAsInt();
			this.myViews = totals.get("views").getAsInt();
			this.myDownloads = totals.get("downloads").getAsInt();
			this.myLikes = totals.get("likes").getAsInt();
			this.myFollowers = totals.get("followers").getAsInt();
			this.myRating = totals.has("rating") ? totals.get("rating").getAsDouble() : 0.0;
			this.myRatingCount = totals.has("ratingCount") ? totals.get("ratingCount").getAsInt() : 0;

			JsonArray days = data.getAsJsonArray("days");
			this.myDays = new String[days.size()];

			for (int i = 0; i < days.size(); i++) {
				this.myDays[i] = days.get(i).getAsString();
			}

			JsonObject series = data.getAsJsonObject("series");
			this.myViewSeries = toIntArray(series.getAsJsonArray("views"));
			this.myDownloadSeries = toIntArray(series.getAsJsonArray("downloads"));
			this.myLikeSeries = toIntArray(series.getAsJsonArray("likes"));
			this.myStarSeries = series.has("stars") ? toIntArray(series.getAsJsonArray("stars")) : new int[0];

			this.myPostSeries.clear();

			if (data.has("postSeries") && data.get("postSeries").isJsonObject()) {
				JsonObject perPost = data.getAsJsonObject("postSeries");

				for (Map.Entry<String, JsonElement> entry : perPost.entrySet()) {
					JsonObject s = entry.getValue().getAsJsonObject();
					this.myPostSeries.put(entry.getKey(), new int[][]{
							toIntArray(s.getAsJsonArray("views")),
							toIntArray(s.getAsJsonArray("downloads")),
							toIntArray(s.getAsJsonArray("likes")),
							s.has("stars") ? toIntArray(s.getAsJsonArray("stars")) : new int[0],
					});
				}
			}

			if (this.selectedDashPost != null && !this.myPostSeries.containsKey(this.selectedDashPost)) {
				this.selectedDashPost = null;
			}

			this.myPosts.clear();

			for (JsonElement element : data.getAsJsonArray("posts")) {
				JsonObject p = element.getAsJsonObject();
				this.myPosts.add(new SchematicEntry(
						p.get("id").getAsString(), p.get("title").getAsString(),
						p.has("thumbnailName") ? p.get("thumbnailName").getAsString() : "",
						p.has("poster") ? p.get("poster").getAsString() : "",
						"", Category.fromName(p.get("category").getAsString()), 0, 0, 0, 0,
						p.get("downloads").getAsInt(), p.get("likes").getAsInt(), p.get("postedAt").getAsLong(),
						"", 0, -1, -1, false,
						p.has("thumbnailUrl") && !p.get("thumbnailUrl").isJsonNull() ? p.get("thumbnailUrl").getAsString() : null,
						List.of(), null, null, 0L, false, 0.0, p.has("views") ? p.get("views").getAsInt() : 0,
					0.0, 0, 0));
			}
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.debug("Could not apply my stats", e);
		}
	}

	private static int[] toIntArray(JsonArray array) {
		int[] out = new int[array.size()];

		for (int i = 0; i < array.size(); i++) {
			out[i] = array.get(i).getAsInt();
		}

		return out;
	}

	private void uploadingButton(GuiGraphics ctx, Rect rect) {
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.ACCENT);

		float fraction = Math.max(0.0F, Math.min(1.0F, (float) Backend.uploadFraction()));
		int fillWidth = Math.round((rect.width - 2) * fraction);

		if (fillWidth > 0) {
			Theme.roundedRect(ctx, rect.x + 1, rect.y + 1, fillWidth, rect.height - 2, Theme.RADIUS_PILL, Theme.DOWNLOAD_FILL);
		}

		int percent = Math.round(fraction * 100.0F);
		long elapsed = System.currentTimeMillis() - this.uploadStartedAt;
		String dots = ".".repeat((int) (System.currentTimeMillis() / 400 % 3) + 1);
		String text = "Uploading " + percent + "%";

		if (fraction > 0.05F && fraction < 0.95F) {
			long remaining = Math.max(1, (long) (elapsed / 1000.0 * (1.0 - fraction) / fraction));
			text += "  ~" + remaining + "s";
		}

		String label = Theme.bold(text + dots);
		Theme.text(ctx, this.font, label, rect.x + (rect.width - this.font.width(label)) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.ON_ACCENT);
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

	private void renderSettings(GuiGraphics ctx, int mouseX, int mouseY) {
		int top = TOP_BAR_HEIGHT + 12;
		int bottom = this.height - OUTER_MARGIN;
		int formWidth = Math.min(this.contentWidth - 48, 420);
		int formX = this.contentX + 24;

		ctx.enableScissor(this.contentX, top, this.contentX + this.contentWidth, bottom);

		int startY = top - Math.round(this.scroll);
		int y = startY;

		Theme.textScaled(ctx, this.font, Theme.bold("Settings"), formX, y, 1.5F, Theme.TEXT);
		y += 36;

		y = this.settingRow(ctx, this.soundsToggle, "Sound effects",
				"Toggle on/off sound effects like button clicks when opening menus.",
				Settings.sounds(), formX, y, formWidth, mouseX, mouseY);
		y = this.volumeSlider(ctx, formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.overwriteToggle, "Confirm before overwriting",
				"Confirm first when overwriting a file that contains the same name, when downloading "
						+ "a new schematic.", Settings.confirmOverwrite(),
				formX, y, formWidth, mouseX, mouseY);
		y = this.settingsSectionHeader(ctx, "Notifications", formX, y);
		y = this.settingRow(ctx, this.toastsToggle, "Show toasts",
				"Show the slide-in notification cards in the corner for things like downloads and follows.",
				Settings.toasts(), formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.notificationsToggle, "New post alerts",
				"Get a toast when a creator you follow posts a new schematic.",
				Settings.notifications(), formX, y, formWidth, mouseX, mouseY);

		if (UploaderAccess.unlocked()) {
			y = this.settingRow(ctx, this.creatorNotificationsToggle, "Follow & like alerts",
					"As a creator, get a toast when someone follows you or likes one of your posts.",
					Settings.creatorAlerts(), formX, y, formWidth, mouseX, mouseY);
		} else {
			this.creatorNotificationsToggle.set(0, 0, 0, 0);
		}

		y = this.settingsSectionHeader(ctx, "Grid density", formX, y);

		for (String row : this.wrap("Changing the grid density decides how many posts can fit on one row, "
				+ "compact provides more posts but smaller thumbnails, while large gives you a great view of "
				+ "the thumbnails.", formWidth, 5)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 2;
		}

		y += 8;
		int densityWidth = this.font.width(Theme.bold("Grid: Comfortable")) + 16;
		this.gridDensityButton.set(formX, y, densityWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.gridDensityButton, "Grid: " + Settings.gridDensityLabel(), mouseX, mouseY, false);

		y += FIELD_HEIGHT;
		y = this.settingsSectionHeader(ctx, "Download folder", formX, y);

		for (String row : this.wrap("The Download folder is meant to be your Schematic folder where you can "
				+ "easily store or access your schematics in Litematica. It is automatically assigned to this "
				+ "client's schematic folder, but you can change it if you prefer to download to a different "
				+ "location.", formWidth, 6)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 2;
		}

		y += 6;
		for (String row : this.wrap(Settings.downloadDirectory().toString(), formWidth, 2)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_MUTE);
			y += this.font.lineHeight + 2;
		}

		y += 8;
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

		y += FIELD_HEIGHT;
		y = this.settingsSectionHeader(ctx, "Cache", formX, y);

		for (String row : this.wrap("Clearing the cache frees the memory used by loaded thumbnails and 3D "
				+ "previews; they reload when next shown.", formWidth, 4)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 2;
		}

		y += 8;
		int clearWidth = this.font.width(Theme.bold("Clear cache")) + 16;
		this.clearCacheButton.set(formX, y, clearWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.clearCacheButton, "Clear cache", mouseX, mouseY, false);

		y += FIELD_HEIGHT;
		y = this.settingsSectionHeader(ctx, "Privacy & updates", formX, y);

		y = this.settingRow(ctx, this.updateLockToggle, "Require the latest version",
				"When on, the mod disables itself until you install an update the developers have "
						+ "marked as required. Turn off to only be notified of updates.",
				Settings.updateLockEnabled(), formX, y, formWidth, mouseX, mouseY);

		for (String row : this.wrap("An anonymous per-install ID lets the server de-duplicate your "
				+ "likes, downloads, views and ratings. It is not tied to your Minecraft account. "
				+ "Reset it to start fresh.", formWidth, 4)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 2;
		}

		y += 8;
		int resetWidth = this.font.width(Theme.bold("Reset identifier")) + 16;
		this.resetTokenButton.set(formX, y, resetWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.resetTokenButton, "Reset identifier", mouseX, mouseY, false);
		y += FIELD_HEIGHT;

		y = this.settingsSectionHeader(ctx, "Terms of service", formX, y);

		for (String row : this.wrap("Review the terms you agreed to, or withdraw your agreement - which "
				+ "disables the online features.", formWidth, 3)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 2;
		}

		y += 8;
		int termsWidth = this.font.width(Theme.bold("Review terms")) + 16;
		this.termsButton.set(formX, y, termsWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.termsButton, "Review terms", mouseX, mouseY, false);
		y += FIELD_HEIGHT + 8;

		ctx.disableScissor();

		int contentHeight = y - startY;
		this.maxScroll = Math.max(0.0F, contentHeight - (bottom - top));
		this.scroll = Math.min(this.scroll, this.maxScroll);

		if (this.maxScroll > 0.0F) {
			int trackHeight = bottom - top;
			int barHeight = Math.max(16, Math.round(trackHeight * (trackHeight / (float) (trackHeight + this.maxScroll))));
			int barY = top + Math.round((trackHeight - barHeight) * (this.scroll / this.maxScroll));
			int barX = Math.min(this.contentX + this.contentWidth + 2, this.width - 4);
			this.drawScrollThumb(ctx, SCROLLBAR_MAIN, barX, 3, barY, barHeight, 1, top, trackHeight, Theme.HAIRLINE);
		}

		int rightX = formX + formWidth + 24;
		int rightWidth = this.contentX + this.contentWidth - rightX;

		if (rightWidth >= 170) {
			this.renderCreditsPanel(ctx, rightX, top, rightWidth, bottom);
		}
	}

	private void renderCreditsPanel(GuiGraphics ctx, int x, int top, int width, int bottom) {
		ctx.enableScissor(x, top, x + width, bottom);

		int y = top;
		Theme.text(ctx, this.font, Theme.bold("Credits"), x, y, Theme.TEXT);
		y += this.font.lineHeight + 8;

		List<RemoteContent.Credit> credits = RemoteContent.credits();

		if (credits.isEmpty()) {
			String message = RemoteContent.loaded() ? "No credits yet." : "Loading credits.";
			Theme.text(ctx, this.font, message, x, y, Theme.TEXT_MUTE);
			ctx.disableScissor();
			return;
		}

		int head = 24;

		for (RemoteContent.Credit credit : credits) {
			if (y > bottom) {
				break;
			}

			Identifier avatar = ImageStore.avatar(credit.avatarUrl());

			if (avatar != null) {
				Theme.image(ctx, avatar, x, y, head, head, 64, 64);
			} else {
				Theme.roundedRect(ctx, x, y, head, head, 4, Theme.SURFACE_ELEVATED);
			}

			int textX = x + head + 8;
			int textWidth = x + width - textX;
			Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, credit.displayName(), textWidth)),
					textX, y + 2, Theme.TEXT);

			if (!credit.role().isBlank()) {
				Theme.text(ctx, this.font, Theme.clip(this.font, credit.role(), textWidth),
						textX, y + 2 + this.font.lineHeight + 2, Theme.ACCENT_BRIGHT);
			}

			int lineY = y + head + 4;

			for (String row : this.wrap(credit.description(), width, 4)) {
				Theme.text(ctx, this.font, row, x, lineY, Theme.TEXT_ASH);
				lineY += this.font.lineHeight + 2;
			}

			y = lineY + 12;
		}

		ctx.disableScissor();
	}

	private int settingsSectionHeader(GuiGraphics ctx, String label, int x, int y) {
		y += 20;
		Theme.text(ctx, this.font, Theme.bold(label), x, y, Theme.TEXT);
		return y + this.font.lineHeight + 7;
	}

	private void updateBanner() {
		RemoteContent.Announcement announcement = RemoteContent.announcement();
		String activeId = announcement != null && !announcement.id().isBlank()
				&& !announcement.id().equals(Settings.dismissedAnnouncement()) ? announcement.id() : null;

		if (activeId != null && !activeId.equals(this.bannerId)) {
			this.bannerId = activeId;
			this.bannerAnnouncement = announcement;
			this.bannerEntering = true;
			this.bannerActive = true;
			this.bannerAnimStart = System.currentTimeMillis();
		} else if (this.bannerActive && this.bannerEntering && activeId == null && this.bannerId != null) {
			this.bannerEntering = false;
			this.bannerAnimStart = System.currentTimeMillis();
		}
	}

	private void dismissBanner() {
		if (this.bannerId != null) {
			Settings.dismissAnnouncement(this.bannerId);
		}

		this.bannerEntering = false;
		this.bannerAnimStart = System.currentTimeMillis();
	}

	private void renderBanner(GuiGraphics ctx, int mouseX, int mouseY) {
		if (!this.bannerActive || this.bannerAnnouncement == null) {
			return;
		}

		long elapsed = System.currentTimeMillis() - this.bannerAnimStart;
		float t = Math.max(0.0F, Math.min(1.0F, elapsed / 420.0F));
		float progress;

		if (this.bannerEntering) {
			progress = easeOutBack(t);
		} else {
			if (t >= 1.0F) {
				this.bannerActive = false;
				return;
			}

			progress = 1.0F - easeInBack(t);
		}

		RemoteContent.Announcement announcement = this.bannerAnnouncement;
		int pad = 12;
		int cardWidth = Math.min(this.contentWidth - 16, 430);
		int cardX = this.contentX + (this.contentWidth - cardWidth) / 2;

		List<String> desc = this.wrap(announcement.description(), cardWidth - pad * 2 - 4, 3);
		int titleRow = this.font.lineHeight + 2;
		int cardHeight = pad + titleRow + 6 + Math.max(1, desc.size()) * (this.font.lineHeight + 2) + pad;

		int restY = TOP_BAR_HEIGHT + 8;
		int hiddenY = -cardHeight - 6;
		int cardY = Math.round(hiddenY + (restY - hiddenY) * progress);

		int accent = parseColor(announcement.color(), Theme.ACCENT);

		Theme.roundedRect(ctx, cardX, cardY, cardWidth, cardHeight, Theme.RADIUS_CARD, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, cardX, cardY, cardWidth, cardHeight, Theme.RADIUS_CARD, Theme.HAIRLINE);
		ctx.fill(cardX + 1, cardY + 1, cardX + 5, cardY + cardHeight - 1, accent);

		int tx = cardX + pad + 4;
		int ty = cardY + pad;

		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, announcement.title(), cardWidth - pad * 2 - 70)),
				tx, ty, Theme.TEXT);

		if (!announcement.tag().isBlank()) {
			int tagX = tx + this.font.width(Theme.bold(announcement.title())) + 8;
			int tagWidth = this.font.width(announcement.tag()) + 12;

			if (tagX + tagWidth < cardX + cardWidth - 24) {
				Theme.roundedRect(ctx, tagX, ty - 2, tagWidth, this.font.lineHeight + 4, Theme.RADIUS_PILL, accent);
				Theme.text(ctx, this.font, announcement.tag(), tagX + 6, ty, Theme.ON_ACCENT);
			}
		}

		int closeSize = 14;
		int closeX = cardX + cardWidth - pad - closeSize + 4;
		int closeY = cardY + pad - 3;
		this.bannerClose.set(closeX, closeY, closeSize, closeSize);
		boolean hover = this.bannerClose.contains(mouseX, mouseY);
		int closeColor = hover ? Theme.TEXT : Theme.TEXT_MUTE;
		this.drawLine(ctx, closeX + 3, closeY + 3, closeX + closeSize - 3, closeY + closeSize - 3, closeColor);
		this.drawLine(ctx, closeX + 3, closeY + closeSize - 3, closeX + closeSize - 3, closeY + 3, closeColor);

		int lineY = cardY + pad + titleRow + 6;

		for (String row : desc) {
			Theme.text(ctx, this.font, row, tx, lineY, Theme.TEXT_ASH);
			lineY += this.font.lineHeight + 2;
		}
	}

	private static float easeOutBack(float t) {
		float c1 = 1.70158F;
		float c3 = c1 + 1.0F;
		float p = t - 1.0F;
		return 1.0F + c3 * p * p * p + c1 * p * p;
	}

	private static float easeInBack(float t) {
		float c1 = 1.70158F;
		float c3 = c1 + 1.0F;
		return c3 * t * t * t - c1 * t * t;
	}

	private static int parseColor(String hex, int fallback) {
		if (hex == null) {
			return fallback;
		}

		String value = hex.trim();

		if (value.startsWith("#")) {
			value = value.substring(1);
		}

		if (value.length() != 6) {
			return fallback;
		}

		try {
			return 0xFF000000 | Integer.parseInt(value, 16);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private String effectiveTermsBody() {
		RemoteContent.Terms terms = RemoteContent.terms();

		if (terms != null && !terms.body().isBlank()) {
			return terms.body();
		}

		String cached = Settings.cachedTermsBody();
		return cached != null ? cached : TERMS_BODY;
	}

	// Master UI volume slider (drag the knob or click the track).
	private int volumeSlider(GuiGraphics ctx, int x, int y, int width, int mouseX, int mouseY) {
		Theme.text(ctx, this.font, "Master volume", x, y, Theme.TEXT);
		String pct = Settings.uiVolume() + "%";
		Theme.text(ctx, this.font, pct, x + width - this.font.width(pct), y, Theme.TEXT_MUTE);
		y += this.font.lineHeight + 8;

		int trackY = y + 2;
		this.volumeSliderTrack.set(x, y - 4, width, 14);
		Theme.roundedRect(ctx, x, trackY, width, 4, 2, Theme.SURFACE_ELEVATED);

		float frac = Settings.uiVolumeFraction();
		int fillW = Math.round(width * frac);
		Theme.roundedRect(ctx, x, trackY, Math.max(2, fillW), 4, 2, Theme.ACCENT);

		int knobX = x + fillW;
		int knobR = 5;
		boolean active = this.draggingVolume || this.volumeSliderTrack.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, knobX - knobR, trackY + 2 - knobR, knobR * 2, knobR * 2, knobR,
				active ? Theme.ACCENT_BRIGHT : Theme.ON_ACCENT);

		return y + 14 + 20;
	}

	private int settingRow(GuiGraphics ctx, Rect rect, String label, String hint, boolean on,
			int x, int y, int width, int mouseX, int mouseY) {
		rect.set(x, y, width, FIELD_HEIGHT);
		this.toggle(ctx, rect, label, on, mouseX, mouseY);
		y += FIELD_HEIGHT + 7;

		for (String row : this.wrap(hint, width, 2)) {
			Theme.text(ctx, this.font, row, x, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 2;
		}

		return y + 24;
	}

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
			this.drawScrollThumb(ctx, SCROLLBAR_MAIN, barX, 3, barY, barHeight, 1, top, trackHeight, Theme.HAIRLINE);
		}
	}

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

	private void renderDetail(GuiGraphics ctx, int mouseX, int mouseY) {
		SchematicEntry entry = this.detail;

		if (entry == null) {
			return;
		}

		long open = System.currentTimeMillis() - this.detailOpenedAt;
		float fade = Math.min(1.0F, Math.max(0.0F, open / 140.0F));
		ctx.fill(0, 0, this.width, this.height, ((int) (0x99 * fade) << 24));

		int modalWidth = Math.min(this.width - 24, this.detailModel ? 860 : 688);
		int modalHeight = Math.min(this.height - 24, this.detailModel ? 453 : 363);
		int x = (this.width - modalWidth) / 2;
		int y = (this.height - modalHeight) / 2;
		int pad = 12;
		this.detailBounds.set(x, y, modalWidth, modalHeight);

		Theme.roundedRect(ctx, x, y, modalWidth, modalHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);

		int imageWidth = Math.round((modalWidth - pad * 3) * (this.detailModel ? 0.64F : 0.56F));
		int imageHeight = imageHeight(imageWidth);

		if (this.detailModel) {
			SchematicPreview.request(entry.schematicSlot(), this.detailYaw, this.detailPitch, this.detailZoom,
					this.cutaway, this.freeLook, this.freeEye, this.detailLayer, this.orbiting || this.draggingLayer);
			Identifier model = SchematicPreview.texture(entry.schematicSlot());

			if (model != null) {
				ctx.fill(x + pad, y + pad, x + pad + imageWidth, y + pad + imageHeight, 0xFF10151A);
				Theme.image(ctx, model, x + pad, y + pad, imageWidth, imageHeight,
						SchematicPreview.WIDTH, SchematicPreview.HEIGHT);
			} else {
				Theme.blueprintPlaceholder(ctx, x + pad, y + pad, imageWidth, imageHeight);
				int slot = entry.schematicSlot();
				String message;

				if (!SchematicPreview.hasSchematic(slot)) {
					message = "No schematic files found";
				} else if (SchematicPreview.failed(slot)) {
					message = (SchematicPreview.hosted(slot)
							? "Can't connect. Click to retry."
							: "Couldn't load preview. Click to retry.") + " (" + Errors.PREVIEW + ")";
				} else {
					message = "Rendering" + ".".repeat((int) (System.currentTimeMillis() / 400 % 3) + 1);
				}

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
				? ""
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

		String age = entry.agoLabel();
		int ageWidth = this.font.width(age);
		Theme.text(ctx, this.font, age, x + modalWidth - pad - ageWidth, y + pad, Theme.TEXT_ASH);

		Theme.text(ctx, this.font, Theme.bold(Theme.clipBold(this.font, entry.title(), infoWidth - ageWidth - 6)),
				infoX, line, Theme.TEXT);
		line += this.font.lineHeight + 11;

		if (this.followConfirm && System.currentTimeMillis() - this.followConfirmAt > 3000L) {
			this.followConfirm = false;
		}

		boolean following = Follows.isFollowing(entry.poster());
		String followLabel = !following ? "Follow" : (this.followConfirm ? "Unfollow?" : "Following");
		int followWidth = this.font.width(Theme.bold(followLabel)) + 12;
		int viewWidth = this.font.width(Theme.bold("View Profile")) + 12;
		this.detailFollow.set(infoX + infoWidth - followWidth, line - 1, followWidth, 12);
		this.detailViewProfile.set(this.detailFollow.x - 6 - viewWidth, line - 1, viewWidth, 12);
		this.pillButton(ctx, this.detailFollow, followLabel, mouseX, mouseY, following && !this.followConfirm);
		this.pillButton(ctx, this.detailViewProfile, "View Profile", mouseX, mouseY, false);

		String postedBy = "Posted by " + entry.poster();
		int postedRoom = Math.max(20, this.detailViewProfile.x - infoX - 6);
		this.detailPosterRect.set(infoX, line, Math.min(this.font.width(postedBy), postedRoom), this.font.lineHeight);
		boolean posterHover = this.detailPosterRect.contains(mouseX, mouseY);
		Theme.text(ctx, this.font, Theme.clip(this.font, postedBy, postedRoom),
				infoX, line, posterHover ? Theme.ACCENT_BRIGHT : Theme.TEXT_MUTE);
		line += this.font.lineHeight + 8;

		Theme.text(ctx, this.font, Theme.clip(this.font, "Designed by " + entry.designer(), infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 16;

		line = this.metaRow(ctx, "Dimensions", entry.dimensionsLabel(), infoX, line, infoWidth) + 8;
		line = this.metaRow(ctx, "Total Blocks", entry.blockCountLabel(), infoX, line, infoWidth) + 8;
		line = this.metaRow(ctx, "Volume", entry.volumeLabel(), infoX, line, infoWidth) + 8;

		Theme.text(ctx, this.font, "Views", infoX, line, Theme.TEXT_ASH);
		String views = SchematicEntry.compact(entry.views());
		int viewsWidth = this.font.width(views);
		Theme.text(ctx, this.font, views, infoX + infoWidth - viewsWidth, line, Theme.TEXT);
		Theme.eyeGlyph(ctx, infoX + infoWidth - viewsWidth - Theme.EYE_GLYPH_WIDTH - 3, line + 2, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 9;

		Theme.text(ctx, this.font, "Downloads", infoX, line, Theme.TEXT_ASH);
		String downloads = entry.downloadsLabel();
		int downloadsWidth = this.font.width(downloads);
		Theme.text(ctx, this.font, downloads, infoX + infoWidth - downloadsWidth, line, Theme.TEXT);
		Theme.downloadGlyph(ctx, infoX + infoWidth - downloadsWidth - Theme.DOWNLOAD_GLYPH_WIDTH - 3,
				line + 2, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 9;

		boolean liked = isLikedBy(entry);
		String likeCount = SchematicEntry.compact(likesOf(entry));
		Theme.text(ctx, this.font, "Likes", infoX, line, Theme.TEXT_ASH);
		int likeWidth = this.font.width(likeCount);
		Theme.text(ctx, this.font, likeCount, infoX + infoWidth - likeWidth, line, liked ? Theme.ACCENT_BRIGHT : Theme.TEXT);
		this.detailHeart.set(infoX + infoWidth - likeWidth - HEART_SIZE - 4, line - 1, HEART_SIZE, HEART_SIZE);
		Theme.heartPopped(ctx, this.detailHeart.x, this.detailHeart.y, liked, popAge(entry));
		line += this.font.lineHeight + 9;

		// Average rating (stat), total ratings (stat), and the interactive rating widget.
		Theme.text(ctx, this.font, "Avg stars", infoX, line, Theme.TEXT_ASH);
		String avg = this.detailStarCount > 0 ? String.format(Locale.ROOT, "%.1f", this.detailStarAvg) : "-";
		int avgWidth = this.font.width(avg);
		Theme.text(ctx, this.font, avg, infoX + infoWidth - avgWidth, line, Theme.TEXT);
		Theme.textScaled(ctx, this.font, STAR_FULL, infoX + infoWidth - avgWidth - 9, line, 0.9F, Theme.STAT_STARS);
		line += this.font.lineHeight + 9;

		Theme.text(ctx, this.font, "Total stars", infoX, line, Theme.TEXT_ASH);
		// Total stars given = sum of every rating (average x number of ratings).
		String total = this.detailStarCount > 0 ? formatStars(this.detailStarAvg * this.detailStarCount) : "0";
		Theme.text(ctx, this.font, total, infoX + infoWidth - this.font.width(total), line, Theme.TEXT);
		line += this.font.lineHeight + 9;

		float starScale = 2.0F;
		int starRowWidth = 5 * this.starCellWidth(starScale);
		int starRowHeight = Math.round(this.font.lineHeight * starScale);
		Theme.text(ctx, this.font, "Your rating", infoX, line + (starRowHeight - this.font.lineHeight) / 2,
				Theme.TEXT_ASH);
		this.drawStars(ctx, infoX + infoWidth - starRowWidth, line, this.detailMyStars, starScale, true,
				mouseX, mouseY, this.detailStarRects);
		line += starRowHeight + 6;

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

		int downloadWidth = this.font.width(Theme.bold("Downloading")) + 18;
		String previewLabel = this.detailModel ? "Pictures" : "3D preview";
		int previewWidth = this.font.width(Theme.bold(previewLabel)) + 18;
		int closeWidth = this.font.width(Theme.bold("Close")) + 18;
		String saveLabel = isSaved(entry) ? "Saved" : "Save for later";
		int saveWidth = this.font.width(Theme.bold(saveLabel)) + 18;

		int loadWidth = this.font.width(Theme.bold("Load")) + 18;
		int collectWidth = this.font.width(Theme.bold("Collections")) + 16;

		this.detailClose.set(x + pad, buttonY, closeWidth, 16);
		this.detailSave.set(this.detailClose.x + closeWidth + 6, buttonY, saveWidth, 16);
		this.detailCollection.set(this.detailSave.x + saveWidth + 6, buttonY, collectWidth, 16);
		this.detailDownload.set(x + modalWidth - pad - downloadWidth, buttonY, downloadWidth, 16);
		this.detailPreview3d.set(this.detailDownload.x - 6 - previewWidth, buttonY, previewWidth, 16);
		this.detailLoad.set(this.detailPreview3d.x - 6 - loadWidth, buttonY, loadWidth, 16);

		this.pillButton(ctx, this.detailClose, "Close", mouseX, mouseY, false);
		this.saveButton(ctx, this.detailSave, isSaved(entry), mouseX, mouseY);
		this.pillButton(ctx, this.detailCollection, "Collections", mouseX, mouseY, this.collectionMenuOpen);
		this.pillButton(ctx, this.detailLoad, "Load", mouseX, mouseY, true);
		this.pillButton(ctx, this.detailPreview3d, previewLabel, mouseX, mouseY, false);
		this.downloadButton(ctx, this.detailDownload, entry, mouseX, mouseY);

		if (this.collectionMenuOpen) {
			this.renderCollectionMenu(ctx, mouseX, mouseY, entry);
		}

		if (this.detailLoad.contains(mouseX, mouseY)) {
			String tip = "Loads the schematic directly into minecraft";
			int tipWidth = this.font.width(tip) + 8;
			int tipX = Math.max(x + 2, Math.min(this.detailLoad.x, x + modalWidth - tipWidth - 2));
			int tipY = buttonY - 16;
			Theme.roundedRect(ctx, tipX, tipY, tipWidth, 12, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
			Theme.roundedOutline(ctx, tipX, tipY, tipWidth, 12, Theme.RADIUS_PILL, Theme.HAIRLINE);
			Theme.text(ctx, this.font, tip, tipX + 4, tipY + 2, Theme.TEXT);
		}

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

		if (flagHover) {
			String tip = "Report this Post";
			int tipWidth = this.font.width(tip) + 8;
			int tipX = this.detailReport.x + flagSize + 3;
			int tipY = this.detailReport.y + (flagSize - 12) / 2;
			Theme.roundedRect(ctx, tipX, tipY, tipWidth, 12, Theme.RADIUS_PILL, 0xF00F1114);
			Theme.text(ctx, this.font, tip, tipX + 4, tipY + 2, Theme.TEXT);
		}
	}

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
		this.reportBounds.set(x, y, cardWidth, cardHeight);

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

		rowY += reasonsToDivider - reasonGap;
		ctx.fill(x + pad, rowY, x + cardWidth - pad, rowY + 1, Theme.HAIRLINE);
		rowY += 1 + dividerToCancel;

		this.reportCancel.set(x + pad, rowY, cardWidth - pad * 2, rowHeight);
		this.pillButton(ctx, this.reportCancel, "Cancel", mouseX, mouseY, false);
	}

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

	private List<String> wrapContext(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (int i = 0; i < text.length(); i++) {
			current.append(text.charAt(i));

			if (this.font.width(current.toString()) <= width) {
				continue;
			}

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

	private void renderReportContext(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 14;
		int line = this.font.lineHeight;
		int cardWidth = 236;
		int boxInner = cardWidth - pad * 2 - 12;

		List<String> lines = this.wrapContext(this.reportContext, boxInner);
		int textLines = Math.max(1, lines.size());
		int boxHeight = 8 + textLines * (line + 1) + 6;

		int cardHeight = pad + line + 4 + line + 8 + boxHeight + 6 + line + 10 + 16 + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.reportBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);

		int ty = y + pad;
		Theme.text(ctx, this.font, Theme.bold("Context"), x + pad, ty, Theme.TEXT);
		ty += line + 4;
		Theme.text(ctx, this.font, "Explain the report - under 100 characters.", x + pad, ty, Theme.TEXT_ASH);
		ty += line + 8;

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
		this.overwriteBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);

		int pad = 12;
		Theme.text(ctx, this.font, Theme.bold("Replace existing file?"), x + pad, y + pad, Theme.TEXT);

		int textY = y + pad + this.font.lineHeight + 4;

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

	private void renderDesignerWarn(GuiGraphics ctx, int mouseX, int mouseY) {
		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int pad = 14;
		int line = this.font.lineHeight;
		int cardWidth = 252;
		List<String> body = this.wrap("You haven't set a designer. This post will be marked as Unknown.",
				cardWidth - pad * 2, 3);

		int boxSize = 9;
		int toggleRowHeight = boxSize + 8;
		int titleToBody = 12;
		int bodyToToggle = 16;
		int toggleToDivider = 12;
		int dividerToButtons = 12;
		int buttonHeight = 16;
		int bodyHeight = body.size() * (line + 1);

		int cardHeight = pad + line + titleToBody + bodyHeight + bodyToToggle + toggleRowHeight
				+ toggleToDivider + 1 + dividerToButtons + buttonHeight + pad;
		int x = (this.width - cardWidth) / 2;
		int y = (this.height - cardHeight) / 2;
		this.designerWarnBounds.set(x, y, cardWidth, cardHeight);

		Theme.roundedRect(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, x, y, cardWidth, cardHeight, Theme.RADIUS_MODAL, Theme.HAIRLINE);
		Theme.text(ctx, this.font, Theme.bold("No designer set"), x + pad, y + pad, Theme.TEXT);

		int bodyY = y + pad + line + titleToBody;

		for (String row : body) {
			Theme.text(ctx, this.font, row, x + pad, bodyY, Theme.ACCENT_BRIGHT);
			bodyY += line + 1;
		}

		int toggleY = bodyY - 1 + bodyToToggle;
		String toggleLabel = "Don't show this again";
		int toggleRowWidth = boxSize + 8 + this.font.width(toggleLabel) + 8;
		this.designerWarnToggle.set(x + pad, toggleY, toggleRowWidth, toggleRowHeight);

		Theme.roundedRect(ctx, x + pad, toggleY, toggleRowWidth, toggleRowHeight, Theme.RADIUS_PILL, Theme.SURFACE_ELEVATED);
		Theme.roundedOutline(ctx, x + pad, toggleY, toggleRowWidth, toggleRowHeight, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);

		int boxX = x + pad + 5;
		int boxY = toggleY + (toggleRowHeight - boxSize) / 2;
		Theme.roundedRect(ctx, boxX, boxY, boxSize, boxSize, Theme.RADIUS_PILL,
				this.designerWarnDontShow ? Theme.ACCENT : Theme.SURFACE_CARD);
		Theme.roundedOutline(ctx, boxX, boxY, boxSize, boxSize, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);
		Theme.text(ctx, this.font, toggleLabel, boxX + boxSize + 6, toggleY + (toggleRowHeight - line) / 2, Theme.TEXT);

		int dividerY = toggleY + toggleRowHeight + toggleToDivider;
		ctx.fill(x + pad, dividerY, x + cardWidth - pad, dividerY + 1, Theme.HAIRLINE);

		int buttonY = dividerY + 1 + dividerToButtons;
		int bw = (cardWidth - pad * 2 - 8) / 2;
		this.designerWarnCancel.set(x + pad, buttonY, bw, buttonHeight);
		this.designerWarnUpload.set(x + pad + bw + 8, buttonY, bw, buttonHeight);
		this.pillButton(ctx, this.designerWarnCancel, "Cancel", mouseX, mouseY, false);
		this.pillButton(ctx, this.designerWarnUpload, "Upload anyway", mouseX, mouseY, true);
	}

	private void clickDesignerWarn(double mouseX, double mouseY) {
		if (this.designerWarnToggle.contains(mouseX, mouseY)) {
			this.designerWarnDontShow = !this.designerWarnDontShow;
			Theme.click(0.9F);
			return;
		}

		if (this.designerWarnCancel.contains(mouseX, mouseY)) {
			this.designerWarnOpen = false;
			Theme.click(0.9F);
			return;
		}

		if (this.designerWarnUpload.contains(mouseX, mouseY)) {
			if (this.designerWarnDontShow) {
				Settings.setSkipDesignerWarning(true);
			}

			this.designerWarnOpen = false;
			Theme.click(1.1F);
			this.beginUpload();
			return;
		}

		// Clicking outside the card cancels (warnings default to cancel).
		if (!this.designerWarnBounds.contains(mouseX, mouseY)) {
			this.designerWarnOpen = false;
			Theme.click(0.9F);
		}
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

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();

		this.registerButtonPress(mouseX, mouseY);

		if (event.button() == 0 && this.tryGrabScrollbar(mouseX, mouseY)) {
			return true;
		}

		if (this.backToTopButton.width > 0 && this.backToTopButton.contains(mouseX, mouseY)) {
			this.scroll = 0.0F;
			this.dashScroll = 0.0F;
			Theme.click();
			return true;
		}

		if (this.errorOpen) {
			if (this.errorReport.contains(mouseX, mouseY)) {
				this.openLink(RemoteContent.discord());
			} else if (this.errorClose.contains(mouseX, mouseY) || !this.errorBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.errorOpen = false;
			}

			return true;
		}

		if (this.nameInputOpen) {
			if (this.nameConfirm.contains(mouseX, mouseY)) {
				Theme.click(1.1F);
				this.confirmNameInput();
			} else if (this.nameCancel.contains(mouseX, mouseY) || !this.nameBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.nameInputOpen = false;
				this.namePendingPost = null;
			}

			return true;
		}

		if (this.loadCodeOpen) {
			if (this.loadCodeConfirm.contains(mouseX, mouseY)) {
				Theme.click(1.1F);
				this.confirmLoadCode();
			} else if (this.loadCodeCancel.contains(mouseX, mouseY) || !this.loadCodeBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.loadCodeOpen = false;
			}

			return true;
		}

		if (this.duplicateOpen) {
			if (this.duplicateClose.contains(mouseX, mouseY) || !this.duplicateBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.duplicateOpen = false;
			}

			return true;
		}

		if (this.deleteCollectionOpen) {
			if (this.deleteCollectionConfirm.contains(mouseX, mouseY)) {
				Theme.click(0.9F);

				if (this.deleteCollectionName != null) {
					CollectionStore.delete(this.deleteCollectionName);

					if (this.deleteCollectionName.equals(this.activeCollection)) {
						this.activeCollection = null;
					}
				}

				this.deleteCollectionOpen = false;
				this.deleteCollectionName = null;
				this.layoutChips();
				this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
				this.refilter();
			} else if (this.deleteCollectionCancel.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.deleteCollectionOpen = false;
				this.deleteCollectionName = null;
			}

			return true;
		}

		if (this.collectionOptionsOpen) {
			if (this.collectionOptionsRename.contains(mouseX, mouseY)) {
				Theme.click(1.1F);
				String name = this.collectionOptionsName;
				this.collectionOptionsOpen = false;

				if (name != null) {
					this.openRenameCollection(name);
				}
			} else if (this.collectionOptionsDelete.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.deleteCollectionName = this.collectionOptionsName;
				this.collectionOptionsOpen = false;
				this.deleteCollectionOpen = true;
				this.modalFocus = -1;
			} else if (this.collectionOptionsCancel.contains(mouseX, mouseY)
					|| !this.collectionOptionsBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.collectionOptionsOpen = false;
				this.collectionOptionsName = null;
			}

			return true;
		}

		if (this.renameCollectionOpen) {
			if (this.renameConfirm.contains(mouseX, mouseY)) {
				Theme.click(1.1F);
				this.confirmRename();
			} else if (this.renameCancel.contains(mouseX, mouseY) || !this.renameBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.renameCollectionOpen = false;
				this.renameCollectionFrom = null;
			}

			return true;
		}

		if (this.postOptionsOpen) {
			if (this.postOptionsEdit.contains(mouseX, mouseY)) {
				Theme.click(1.1F);
				String id = this.postOptionsId;
				this.postOptionsOpen = false;

				if (id != null) {
					this.openEditPost(id);
				}
			} else if (this.postOptionsUnpublish.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.unpublishId = this.postOptionsId;
				this.unpublishTitle = this.postOptionsTitle;
				this.postOptionsOpen = false;
				this.unpublishOpen = true;
			} else if (this.postOptionsCancel.contains(mouseX, mouseY)
					|| !this.postOptionsBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.postOptionsOpen = false;
			}

			return true;
		}

		if (this.editPostOpen) {
			if (this.editSave.contains(mouseX, mouseY)) {
				Theme.click(1.1F);
				this.setFocused(null);
				this.confirmEdit();
			} else if (this.editCancel.contains(mouseX, mouseY) || !this.editBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.editPostOpen = false;
				this.editPostId = null;
				this.setFocused(null);
			} else if (this.editCategoryButton.contains(mouseX, mouseY)) {
				Theme.click();
				Category[] tags = Category.tags();
				int index = 0;

				for (int i = 0; i < tags.length; i++) {
					if (tags[i] == this.editCategory) {
						index = i;
						break;
					}
				}

				this.editCategory = tags[(index + 1) % tags.length];
			} else {
				this.focusField(this.editTitleBox, event, doubleClick, mouseX, mouseY);
				this.focusField(this.editThumbnailBox, event, doubleClick, mouseX, mouseY);
				this.focusField(this.editDesignerBox, event, doubleClick, mouseX, mouseY);
				this.focusField(this.editDescriptionBox, event, doubleClick, mouseX, mouseY);
			}

			return true;
		}

		if (this.unpublishOpen) {
			if (this.unpublishConfirm.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.confirmUnpublish();
			} else if (this.unpublishCancel.contains(mouseX, mouseY)
					|| !this.unpublishBounds.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.unpublishOpen = false;
				this.unpublishId = null;
			}

			return true;
		}

		if (event.button() == 1 && this.page == Page.SAVED && this.detail == null && !this.tosOpen
				&& !this.tutorialActive && !this.designerWarnOpen) {
			for (int i = 1; i < this.collectionChips.size(); i++) {
				if (this.collectionChips.get(i).contains(mouseX, mouseY)) {
					List<String> names = CollectionStore.names();

					if (i - 1 < names.size()) {
						this.openCollectionOptions(names.get(i - 1));
						Theme.click(0.9F);
					}

					return true;
				}
			}
		}

		if (this.bannerActive && this.bannerEntering && this.detail == null && !this.tosOpen
				&& !this.tutorialActive && !this.designerWarnOpen && this.bannerClose.contains(mouseX, mouseY)) {
			this.dismissBanner();
			Theme.click(0.9F);
			return true;
		}

		if (this.tosOpen) {
			this.clickTos(mouseX, mouseY);
			return true;
		}

		if (this.tutorialActive) {
			this.clickTutorial(mouseX, mouseY);
			return true;
		}

		if (this.designerWarnOpen) {
			this.clickDesignerWarn(mouseX, mouseY);
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
				if (SchematicPreview.failed(this.detail.schematicSlot())) {
					SchematicPreview.retry(this.detail.schematicSlot());
					Theme.click(0.9F);
					return true;
				}

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

		List<RemoteContent.Link> railLinks = RemoteContent.links();

		for (int i = 0; i < this.railLinkRects.size() && i < railLinks.size(); i++) {
			if (this.railLinkRects.get(i).contains(mouseX, mouseY)) {
				this.openLink(railLinks.get(i).url());
				return true;
			}
		}

		if (this.gridPage()) {
			List<RemoteContent.Partner> partners = RemoteContent.partners();

			for (int i = 0; i < this.partnerRects.size() && i < partners.size(); i++) {
				if (this.partnerRects.get(i).contains(mouseX, mouseY)) {
					this.openLink(partners.get(i).url());
					return true;
				}
			}
		}

		if (this.page == Page.UPLOAD) {
			return this.clickUpload(event, doubleClick, mouseX, mouseY);
		}

		if (this.page == Page.SETTINGS) {
			return this.clickSettings(mouseX, mouseY);
		}

		if (this.page == Page.NEWS) {
			return true;
		}

		if (this.retryButton.contains(mouseX, mouseY)) {
			Theme.click();
			Catalogue.refresh();
			return true;
		}

		if (this.profilePoster != null) {
			if (this.profileBack.contains(mouseX, mouseY)) {
				Theme.click(0.9F);
				this.closeProfile();
				return true;
			}

			if (this.profileFollow.contains(mouseX, mouseY)) {
				String poster = this.profilePoster;
				boolean wasFollowing = Follows.isFollowing(poster);
				Follows.toggle(poster);

				if (wasFollowing) {
					if (this.profileFollowers > 0) {
						this.profileFollowers--;
					}

					Theme.click(0.8F);
					this.verifyUnfollow(poster);
				} else {
					if (this.profileFollowers >= 0) {
						this.profileFollowers++;
					}

					Theme.follow();
					this.verifyFollow(null, poster);
				}

				return true;
			}
		}

		if (this.addingToCollection != null && this.addModeDone.contains(mouseX, mouseY)) {
			String collection = this.addingToCollection;
			this.addingToCollection = null;
			this.page = Page.SAVED;
			this.activeCollection = collection;
			this.setSearch("");
			Theme.click(0.9F);
			this.layoutChips();
			this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
			this.scroll = 0.0F;
			this.refilter();
			return true;
		}

		if (this.sortButton.contains(mouseX, mouseY)) {
			this.sort = this.sort.next();
			Theme.click();
			this.layoutChips();
			this.refilter();
			return true;
		}

		if (this.page == Page.BROWSE && this.downloadFilterButton.contains(mouseX, mouseY)) {
			this.downloadFilter = (this.downloadFilter + 1) % 3;
			this.refreshDownloadedNames();
			Theme.click();
			this.layoutChips();
			this.scroll = 0.0F;
			this.refilter();
			return true;
		}

		if (this.page == Page.SAVED && this.clickCollectionChips(mouseX, mouseY)) {
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
			if (this.addingToCollection != null) {
				boolean nowIn = CollectionStore.toggle(this.addingToCollection, hit.id());
				Theme.click(nowIn ? 1.2F : 0.9F);
			} else if (this.heartAt(hit, mouseX, mouseY)) {
				toggleLike(hit);

				if (this.page == Page.SAVED) {
					this.refilter();
				}
			} else {
				this.openDetail(hit);
			}

			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void openDetail(SchematicEntry entry) {
		this.detail = entry;
		this.collectionMenuOpen = false;
		Backend.viewAsync(entry.id());

		this.detailMyStars = entry.myStars();
		this.detailStarAvg = entry.starAvg();
		this.detailStarCount = entry.starCount();

		RECENT_VIEWED.remove(entry.id());
		RECENT_VIEWED.add(0, entry.id());

		while (RECENT_VIEWED.size() > RECENT_VIEWED_MAX) {
			RECENT_VIEWED.remove(RECENT_VIEWED.size() - 1);
		}

		if (entry.imageUrls().isEmpty()) {
			ImageStore.preload(entry.imageStart(), entry.imageCount());
		} else {
			ImageStore.preload(entry.imageUrls());
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
		this.detailDownloadLocked = false;
		this.status = "";
	}

	private void switchPage(Page target) {
		if (target != this.page) {
			Theme.tab();
		}

		if (target == Page.UPLOAD) {
			// Keep uploadFormOpen so returning to Upload lands back on the form you were
			// filling out rather than resetting to the dashboard.
			this.myStatsLoaded = false;
			this.dashScroll = 0.0F;
		}

		this.page = target;
		this.scroll = 0.0F;
		this.formStatus = "";
		this.addingToCollection = null;
		this.activeCollection = null;
		this.profilePoster = null;
		this.setFocused(null);
		this.relayout();
	}

	private boolean clickUpload(MouseButtonEvent event, boolean doubleClick, double mouseX, double mouseY) {
		if (!UploaderAccess.unlocked()) {
			if (this.unlockButton.contains(mouseX, mouseY)) {
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
			this.selectedDashPost = null;
			this.setFocused(null);
			return true;
		}

		if (!this.uploadFormOpen) {
			if (this.dashUploadButton.contains(mouseX, mouseY)) {
				this.uploadFormOpen = true;
				Theme.click(1.1F);
				return true;
			}

			if (this.legendViewsRect.contains(mouseX, mouseY)) {
				this.graphMode = this.graphMode == 1 ? 0 : 1;
				this.graphAnimStart = System.currentTimeMillis();
				Theme.click(0.9F);
				return true;
			}

			if (this.legendDownloadsRect.contains(mouseX, mouseY)) {
				this.graphMode = this.graphMode == 2 ? 0 : 2;
				this.graphAnimStart = System.currentTimeMillis();
				Theme.click(0.9F);
				return true;
			}

			if (this.legendLikesRect.contains(mouseX, mouseY)) {
				this.graphMode = this.graphMode == 3 ? 0 : 3;
				this.graphAnimStart = System.currentTimeMillis();
				Theme.click(0.9F);
				return true;
			}

			if (this.legendStarsRect.contains(mouseX, mouseY)) {
				this.graphMode = this.graphMode == 4 ? 0 : 4;
				this.graphAnimStart = System.currentTimeMillis();
				Theme.click(0.9F);
				return true;
			}

			Category[] all = Category.values();

			for (int i = 0; i < this.myFilterChips.size() && i < all.length; i++) {
				if (this.myFilterChips.get(i).contains(mouseX, mouseY)) {
					this.myFilter = all[i];
					this.dashScroll = 0.0F;
					Theme.click(0.9F);
					return true;
				}
			}

			if (mouseY >= this.dashViewportTop && mouseY <= this.dashViewportBottom) {
				// The edit button opens the edit/unpublish options for that post.
				for (int i = 0; i < this.myPostEditRects.size() && i < this.myPostCardIds.size(); i++) {
					if (this.myPostEditRects.get(i).contains(mouseX, mouseY)) {
						this.openPostOptions(this.myPostCardIds.get(i));
						Theme.click(0.9F);
						return true;
					}
				}

				// Clicking a post selects it (or deselects if already selected); the graph then
				// shows just that post's history.
				for (int i = 0; i < this.myPostCardRects.size() && i < this.myPostCardIds.size(); i++) {
					if (this.myPostCardRects.get(i).contains(mouseX, mouseY)) {
						String id = this.myPostCardIds.get(i);
						this.selectedDashPost = id.equals(this.selectedDashPost) ? null : id;
						this.graphAnimStart = System.currentTimeMillis();
						Theme.click(this.selectedDashPost != null ? 1.1F : 0.9F);
						return true;
					}
				}
			}

			return true;
		}

		if (this.uploadBackButton.contains(mouseX, mouseY)) {
			this.uploadFormOpen = false;
			this.myStatsLoaded = false;
			Theme.click(0.9F);
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

		if (this.formImageRemove.contains(mouseX, mouseY) && !this.formPictures.isEmpty()) {
			this.removeFormPicture(this.formPicturePreview);
			Theme.click(0.9F);
			return true;
		}

		if (this.formThumbnailButton.width > 0 && this.formThumbnailButton.contains(mouseX, mouseY)) {
			this.formThumbnailIndex = this.formPicturePreview;
			Theme.click(1.1F);
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

		if (this.focusField(this.titleBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.thumbnailBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.designerBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.descriptionBox, event, doubleClick, mouseX, mouseY)) {
			return true;
		}

		// Clicking empty space steps out of any text box, so Ctrl+V pastes a schematic
		// link/image instead of typing into a field.
		this.clearFormFocus();
		return true;
	}

	private void clearFormFocus() {
		this.setFocused(null);

		for (EditBox box : new EditBox[]{this.codeBox, this.titleBox, this.thumbnailBox, this.designerBox, this.descriptionBox}) {
			if (box != null) {
				box.setFocused(false);
			}
		}
	}

	private boolean clickSettings(double mouseX, double mouseY) {
		if (this.volumeSliderTrack.contains(mouseX, mouseY)) {
			this.draggingVolume = true;
			this.setVolumeFromMouse(mouseX);
			Theme.click(1.0F);
			return true;
		}

		if (this.soundsToggle.contains(mouseX, mouseY)) {
			Settings.toggleSounds();

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
		} else if (this.creatorNotificationsToggle.contains(mouseX, mouseY)) {
			Settings.toggleCreatorAlerts();
			Theme.click(1.1F);
		} else if (this.updateLockToggle.contains(mouseX, mouseY)) {
			Settings.toggleUpdateLock();
			Theme.click(1.1F);
		} else if (this.resetTokenButton.contains(mouseX, mouseY)) {
			Settings.resetDeviceToken();
			Theme.click();
			Toasts.push("Identifier reset", "A new anonymous ID was generated.", new ItemStack(Items.NAME_TAG));
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

	private void openLink(String url) {
		if (url == null || url.isBlank()) {
			return;
		}

		Theme.click(1.0F);
		net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this, url, true);
	}

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

		int total = this.detailLayerHeight();
		int layers = Math.max(1, Math.min(total, Math.round(fraction * total)));
		this.detailLayer = (float) layers / total;
	}

	private int detailLayerHeight() {
		int height = this.detail == null ? -1 : SchematicPreview.layerHeight(this.detail.schematicSlot());
		return height > 0 ? height : Math.max(1, this.detail == null ? 1 : this.detail.sizeY());
	}

	private String downloadFileName(SchematicEntry entry) {
		return entry.title() + " - The Schematic Index Addon.litematic";
	}

	private static final String STAR_FULL = "★";
	private static final String STAR_EMPTY = "☆";

	private int starCellWidth(float scale) {
		return Math.round(this.font.width(STAR_FULL) * scale) + 2;
	}

	// Formats a star quantity, snapping to the nearest half and dropping a trailing ".0".
	private static String formatStars(double value) {
		double snapped = Math.round(value * 2.0) / 2.0;
		return snapped == Math.rint(snapped)
				? Integer.toString((int) snapped)
				: String.format(Locale.ROOT, "%.1f", snapped);
	}

	// Draws five stars filled to halfStars/2 (0..5). When rects is given, records each star's hit
	// box for click handling and brightens the hovered one. Half-filled stars are clipped in half.
	private void drawStars(GuiGraphics ctx, int x, int y, int halfStars, float scale, boolean interactive,
			int mouseX, int mouseY, @Nullable Rect[] rects) {
		int cell = this.starCellWidth(scale);
		int glyphW = Math.round(this.font.width(STAR_FULL) * scale);
		int glyphH = Math.round(this.font.lineHeight * scale) + 2;

		for (int i = 0; i < 5; i++) {
			int sx = x + i * cell;

			if (rects != null) {
				rects[i].set(sx, y - 1, cell, glyphH);
			}

			boolean hovered = interactive && rects != null && rects[i].contains(mouseX, mouseY);
			int color = hovered ? 0xFFF6CF63 : Theme.STAT_STARS;
			int fill = Math.max(0, Math.min(2, halfStars - i * 2));

			Theme.textScaled(ctx, this.font, STAR_EMPTY, sx, y, scale, 0xFF60656C);

			if (fill == 2) {
				Theme.textScaled(ctx, this.font, STAR_FULL, sx, y, scale, color);
			} else if (fill == 1) {
				ctx.enableScissor(sx, y - 2, sx + Math.max(1, glyphW / 2), y + glyphH);
				Theme.textScaled(ctx, this.font, STAR_FULL, sx, y, scale, color);
				ctx.disableScissor();
			}
		}
	}

	// Clicking star k sets a full k-star rating; clicking the same star again drops to a half
	// star; a third click on that same star clears the rating entirely.
	private void rateStar(SchematicEntry entry, int starIndex) {
		int full = (starIndex + 1) * 2;
		int value = this.detailMyStars == full ? full - 1 : (this.detailMyStars == full - 1 ? 0 : full);
		this.queueRating(entry, value);
	}

	// Updates the stars instantly and locally; the value is sent once the user stops clicking.
	private void queueRating(SchematicEntry entry, int value) {
		this.detailMyStars = value;
		Theme.rate();
		this.pendingRateId = entry.id();
		this.pendingRateValue = value;
		this.pendingRateAt = System.currentTimeMillis();
	}

	// Sends the pending rating once the user has paused (or immediately when `force`), then folds
	// the server's authoritative average back in - but only if no newer click is queued.
	private void flushPendingRate(boolean force) {
		if (this.pendingRateValue < 0 || this.pendingRateId == null) {
			return;
		}

		if (!force && System.currentTimeMillis() - this.pendingRateAt < RATE_DEBOUNCE_MS) {
			return;
		}

		String id = this.pendingRateId;
		int value = this.pendingRateValue;
		this.pendingRateValue = -1;
		this.pendingRateId = null;

		Thread worker = new Thread(() -> {
			com.google.gson.JsonObject result = Backend.rate(id, value);

			if (result != null) {
				Minecraft.getInstance().execute(() -> {
					// Ignore the response if the user has started rating again in the meantime.
					if (this.pendingRateValue < 0 && this.detail != null && this.detail.id().equals(id)) {
						this.detailStarAvg = result.has("starAvg") ? result.get("starAvg").getAsDouble() : this.detailStarAvg;
						this.detailStarCount = result.has("starCount") ? result.get("starCount").getAsInt() : this.detailStarCount;
						this.detailMyStars = result.has("myStars") ? result.get("myStars").getAsInt() : value;
					}
				});
			}
		}, "schematicindex-rate");
		worker.setDaemon(true);
		worker.start();
	}

	// Rebuilds the set of file names present in the download folder so the "downloaded" filter
	// doesn't hit the disk once per entry (or per keystroke) during refilter.
	private void refreshDownloadedNames() {
		this.downloadedNames.clear();

		try (java.util.stream.Stream<Path> files = Files.list(Settings.downloadDirectory())) {
			files.forEach(p -> this.downloadedNames.add(p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
		} catch (Exception e) {
			SchematicIndexMod.LOGGER.debug("Could not list the download folder", e);
		}
	}

	private boolean isDownloaded(SchematicEntry entry) {
		return this.downloadedNames.contains(
				Download.safeName(this.downloadFileName(entry)).toLowerCase(java.util.Locale.ROOT));
	}

	private String downloadFilterLabel() {
		return switch (this.downloadFilter) {
			case 1 -> "Show: Downloaded";
			case 2 -> "Show: Not yet";
			default -> "Show: All";
		};
	}

	// Follows are optimistic in the UI; confirm with the server on a background thread and
	// revert with an error code if it could not be saved (e.g. the user is offline).
	private void verifyFollow(String postId, String poster) {
		Thread worker = new Thread(() -> {
			int status = Backend.followStatus(postId, poster);
			boolean ok = status >= 200 && status < 300;
			String ign = poster;

			if (ok) {
				com.google.gson.JsonObject creator = Backend.getJson("/creator/" + Backend.encode(poster));

				if (creator != null && creator.has("ign") && !creator.get("ign").isJsonNull()
						&& !creator.get("ign").getAsString().isBlank()) {
					ign = creator.get("ign").getAsString();
				}
			}

			String skinName = ign;
			Minecraft.getInstance().execute(() -> {
				if (ok) {
					ItemStack head = new ItemStack(Items.PLAYER_HEAD);
					head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(skinName));
					Toasts.push("Followed " + poster, "You'll be notified whenever they post a new schematic", head);
				} else {
					if (Follows.isFollowing(poster)) {
						Follows.toggle(poster);
					}

					if (poster.equals(this.profilePoster)) {
						this.fetchCreator(poster);
					}

					this.showError(Errors.FOLLOW);
				}
			});
		}, "schematicindex-follow");
		worker.setDaemon(true);
		worker.start();
	}

	private void verifyUnfollow(String poster) {
		Thread worker = new Thread(() -> {
			int status = Backend.unfollowStatus(poster);
			Minecraft.getInstance().execute(() -> {
				if (status < 200 || status >= 300) {
					if (!Follows.isFollowing(poster)) {
						Follows.toggle(poster);
					}

					if (poster.equals(this.profilePoster)) {
						this.fetchCreator(poster);
					}

					this.showError(Errors.FOLLOW);
				}
			});
		}, "schematicindex-unfollow");
		worker.setDaemon(true);
		worker.start();
	}

	private void loadSchematic(SchematicEntry entry) {
		Theme.click(1.2F);
		Path target = LoadedCache.resolve(Download.safeName(this.downloadFileName(entry)));

		if (Files.exists(target)) {
			this.loadIntoGame(target, entry.title());
			return;
		}

		String url = entry.fileUrl();
		Path source = url == null || url.isBlank() ? SchematicPreview.pathFor(entry.schematicSlot()) : null;
		this.status = "Loading the schematic...";

		Thread worker = new Thread(() -> {
			boolean ok;

			try {
				if (url != null && !url.isBlank()) {
					ok = Backend.download(url, target);
				} else if (source != null) {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					ok = true;
				} else {
					ok = false;
				}
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.debug("Load download failed", e);
				ok = false;
			}

			boolean success = ok && Files.exists(target);
			Minecraft.getInstance().execute(() -> {
				if (success) {
					this.loadIntoGame(target, entry.title());
				} else {
					this.status = "";
					this.showError(Errors.LOAD);
				}
			});
		}, "schematicindex-load");
		worker.setDaemon(true);
		worker.start();
	}

	private void loadIntoGame(Path file, String name) {
		try {
			LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
					file.getParent(), file.getFileName().toString(), FileType.LITEMATICA_SCHEMATIC);

			if (schematic == null) {
				this.showError(Errors.LOAD);
				return;
			}

			SchematicHolder.getInstance().addSchematic(schematic, false);

			Minecraft client = Minecraft.getInstance();
			BlockPos origin = client.player != null ? client.player.blockPosition() : BlockPos.ZERO;
			SchematicPlacement placement = SchematicPlacement.createFor(schematic, origin, name, true, true);
			DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, true);

			this.status = "";
			this.onClose();
		} catch (Throwable e) {
			SchematicIndexMod.LOGGER.warn("Loading into game failed", e);
			this.showError(Errors.LOAD);
		}
	}

	private void startDownload(SchematicEntry entry) {
		if (Settings.confirmOverwrite() && this.pendingOverwrite == null) {
			Download.Progress progress = Download.progress(entry.id());
			boolean alreadyDone = progress != null && progress.state() == Download.State.DONE;

			if (!alreadyDone && Files.exists(Download.resolveTarget(this.downloadFileName(entry)))) {
				this.pendingOverwrite = entry;
				this.modalFocus = -1;
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
			Download.start(entry.id(), this.downloadFileName(entry), url, null);
		} else {
			Path source = SchematicPreview.pathFor(entry.schematicSlot());

			if (source == null) {
				this.status = "No file to download.";
				return;
			}

			Download.start(entry.id(), this.downloadFileName(entry), null, source);
		}

		Backend.downloadAsync(entry.id());
		this.detailDownloadLocked = true;
		this.status = "Downloading the schematic into your selected folder...";
		this.downloadStates.put(entry.id(), Download.State.RUNNING);
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
		if (this.uploading) {
			return;
		}

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

		if (this.designerBox.getValue().trim().isEmpty() && !Settings.skipDesignerWarning()) {
			this.designerWarnOpen = true;
			this.designerWarnDontShow = false;
			return;
		}

		this.beginUpload();
	}

	private void beginUpload() {
		if (this.uploading || this.formSchematic == null || UploaderAccess.code() == null) {
			return;
		}

		String designer = this.designerBox.getValue().trim();

		JsonObject meta = new JsonObject();
		meta.addProperty("title", this.titleBox.getValue().trim());
		meta.addProperty("thumbnailName", this.thumbnailBox.getValue().trim());
		meta.addProperty("designer", designer.isEmpty() ? "Unknown" : designer);
		meta.addProperty("category", this.formCategory.name());
		meta.addProperty("description", this.descriptionBox.getValue().trim());
		meta.addProperty("thumbnailIndex", this.formThumbnailIndex);

		String code = UploaderAccess.code();
		Path schematic = this.formSchematic;
		List<Path> pictures = new ArrayList<>(this.formPictures);
		this.formStatus = "";
		this.uploading = true;
		this.uploadStartedAt = System.currentTimeMillis();

		try {
			this.uploadFileSize = Files.size(schematic);
		} catch (Exception e) {
			this.uploadFileSize = 0L;
		}

		Thread worker = new Thread(() -> {
			Backend.UploadResult result = Backend.upload(code, meta.toString(), schematic, pictures);
			Minecraft.getInstance().execute(() -> {
				this.uploading = false;

				if (result.status() == 201) {
					this.formPictures.clear();
					this.formPictureStart = -1;
					this.formPicturePreview = 0;
					this.formThumbnailIndex = 0;
					this.formSchematic = null;
					this.formSizeX = 0;
					this.formSizeY = 0;
					this.formSizeZ = 0;
					this.formBlockCount = 0;
					this.titleBox.setValue("");
					this.thumbnailBox.setValue("");
					this.designerBox.setValue("");
					this.descriptionBox.setValue("");
					this.formStatus = "";
					this.uploadFormOpen = false;
					clearDraft();
					Catalogue.refresh();
					this.switchPage(Page.BROWSE);
				} else if (result.status() == 409) {
					this.formStatus = "";
					this.duplicateOpen = true;
				} else if (result.message() != null && !result.message().isBlank()) {
					this.formStatus = result.message();
				} else {
					this.formStatus = "Upload failed.";
					this.showError(Errors.UPLOAD);
				}
			});
		}, "schematicindex-upload");
		worker.setDaemon(true);
		worker.start();
	}

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
				this.parseSchematicStats(chosen);
			});
		}, "schematicindex-schematic-picker").start();
	}

	private void parseSchematicStats(Path file) {
		this.formSizeX = 0;
		this.formSizeY = 0;
		this.formSizeZ = 0;
		this.formBlockCount = 0;

		Minecraft.getInstance().submit(() -> {
			try {
				LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
						file.getParent(), file.getFileName().toString(), FileType.LITEMATICA_SCHEMATIC);

				if (schematic != null) {
					var size = schematic.getMetadata().getEnclosingSize();
					this.formSizeX = Math.abs(size.getX());
					this.formSizeY = Math.abs(size.getY());
					this.formSizeZ = Math.abs(size.getZ());
					this.formBlockCount = (int) Math.max(0L, schematic.getMetadata().getTotalBlocks());
				}
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.debug("Could not read schematic stats", e);
			}
		});
	}

	private void removeFormPicture(int index) {
		if (index < 0 || index >= this.formPictures.size()) {
			return;
		}

		List<Path> remaining = new ArrayList<>(this.formPictures);
		remaining.remove(index);

		this.formPictures.clear();
		this.formPictures.addAll(remaining);
		this.formPictureStart = ImageStore.register(remaining);
		this.formPicturePreview = remaining.isEmpty() ? 0 : Math.min(index, remaining.size() - 1);

		// Keep the thumbnail pointing at the same picture (or reset if it was the one removed).
		if (this.formThumbnailIndex == index) {
			this.formThumbnailIndex = 0;
		} else if (this.formThumbnailIndex > index) {
			this.formThumbnailIndex--;
		}

		this.formThumbnailIndex = remaining.isEmpty() ? 0 : Math.min(this.formThumbnailIndex, remaining.size() - 1);
		this.formStatus = remaining.isEmpty()
				? "All pictures removed."
				: remaining.size() + (remaining.size() == 1 ? " picture selected." : " pictures selected.");
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

		if (this.collectionMenuOpen) {
			this.clickCollectionMenu(mouseX, mouseY, entry);
			return;
		}

		if (this.detailCollection.contains(mouseX, mouseY)) {
			Theme.click(1.0F);
			this.collectionMenuOpen = true;
			return;
		}

		if (this.detailPosterRect.contains(mouseX, mouseY) || this.detailViewProfile.contains(mouseX, mouseY)) {
			Theme.click(1.0F);
			this.openProfile(entry.poster());
			return;
		}

		for (int i = 0; i < this.detailStarRects.length; i++) {
			if (this.detailStarRects[i].contains(mouseX, mouseY)) {
				this.rateStar(entry, i);
				return;
			}
		}

		// The close buttons, or a click on the scrim outside the modal card, dismiss it.
		if (this.detailClose.contains(mouseX, mouseY) || this.detailCornerClose.contains(mouseX, mouseY)
				|| !this.detailBounds.contains(mouseX, mouseY)) {
			Theme.click(0.9F);
			this.detail = null;
			this.collectionMenuOpen = false;
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
				this.followConfirm = false;
				this.verifyFollow(entry.id(), entry.poster());
			} else if (!this.followConfirm) {
				this.followConfirm = true;
				this.followConfirmAt = System.currentTimeMillis();
				Theme.click(0.9F);
			} else {
				Follows.toggle(entry.poster());
				Theme.click(0.8F);
				this.followConfirm = false;
				this.verifyUnfollow(entry.poster());
			}
		} else if (this.detailLoad.contains(mouseX, mouseY)) {
			this.loadSchematic(entry);
		} else if (this.detailDownload.contains(mouseX, mouseY)) {
			if (!this.detailDownloadLocked) {
				this.startDownload(entry);
			}
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
				this.reportOpen = false;
				this.reportContextOpen = true;
				this.reportReasonIndex = i;
				this.reportContext = "";
				Theme.click(1.1F);
				return;
			}
		}

		if (this.reportCancel.contains(mouseX, mouseY) || !this.reportBounds.contains(mouseX, mouseY)) {
			this.reportOpen = false;
			Theme.click(0.9F);
		}
	}

	private void clickReportContext(double mouseX, double mouseY) {
		if (this.reportSubmit.contains(mouseX, mouseY)) {
			this.submitReport();
		} else if (!this.reportBounds.contains(mouseX, mouseY)) {
			// Clicking outside the card cancels the report.
			this.reportContextOpen = false;
			this.reportReasonIndex = -1;
			this.reportContext = "";
			Theme.click(0.9F);
		}
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
		} else if (this.overwriteCancel.contains(mouseX, mouseY)
				|| !this.overwriteBounds.contains(mouseX, mouseY)) {
			// Outside the card counts as cancel.
			this.pendingOverwrite = null;
			Theme.click(0.9F);
			this.status = "";
		}
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
		if (this.draggingScrollbar) {
			this.dragScrollbarTo(event.y());
			return true;
		}

		if (this.draggingVolume) {
			this.setVolumeFromMouse(event.x());
			return true;
		}

		if (this.draggingLayer && this.detail != null && this.detailModel) {
			this.setLayerFromMouse(event.x());
			return true;
		}

		if (this.orbiting && this.detail != null && this.detailModel) {
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

		if (this.draggingScrollbar) {
			this.draggingScrollbar = false;
			this.dragScrollChannel = SCROLLBAR_NONE;
			return true;
		}

		if (this.draggingVolume) {
			this.draggingVolume = false;
			Theme.click(1.0F);
			return true;
		}

		return super.mouseReleased(event);
	}

	private void setVolumeFromMouse(double mouseX) {
		int trackX = this.volumeSliderTrack.x;
		int trackW = this.volumeSliderTrack.width;

		if (trackW > 0) {
			Settings.setUiVolume((int) Math.round((mouseX - trackX) / trackW * 100.0));
		}
	}

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
					this.detailFollow, this.detailLoad, this.detailDownload, this.detailPreview3d, this.resetViewButton,
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
			if (this.detailModel && !this.freeLook && this.detailImageRect.contains(mouseX, mouseY)) {
				float factor = scrollY > 0 ? 1.18F : 1.0F / 1.18F;
				this.detailZoom = SchematicPreview.clampZoom(this.detail.schematicSlot(), this.detailZoom * factor);
			}

			return true;
		}

		if (this.page == Page.UPLOAD) {
			if (!this.uploadFormOpen) {
				this.dashScroll = Math.max(0.0F, Math.min(this.dashMaxScroll, this.dashScroll - (float) scrollY * SCROLL_STEP));
			}

			return true;
		}

		this.scroll = Math.max(0.0F, Math.min(this.maxScroll, this.scroll - (float) scrollY * SCROLL_STEP));
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (this.nameInputOpen) {
			if (event.codepoint() >= ' ' && this.nameInput.length() < 40) {
				this.nameInput += event.codepointAsString();
			}

			return true;
		}

		if (this.renameCollectionOpen) {
			if (event.codepoint() >= ' ' && this.renameInput.length() < 40) {
				this.renameInput += event.codepointAsString();
			}

			return true;
		}

		if (this.loadCodeOpen) {
			String typed = event.codepointAsString();

			if (this.loadCodeInput.length() < 5 && !typed.isEmpty() && Character.isLetterOrDigit(typed.charAt(0))) {
				this.loadCodeInput += typed.toUpperCase(Locale.ROOT);
			}

			return true;
		}

		if (this.reportContextOpen) {
			if (event.codepoint() >= ' ' && this.reportContext.length() < REPORT_CONTEXT_MAX) {
				this.reportContext += event.codepointAsString();
			}

			return true;
		}

		return super.charTyped(event);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (this.page != Page.UPLOAD || !this.uploadFormOpen || paths.isEmpty()) {
			super.onFilesDrop(paths);
			return;
		}

		Path schematic = null;
		List<Path> images = new ArrayList<>();

		for (Path path : paths) {
			String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

			if (name.endsWith(".litematic")) {
				schematic = path;
			} else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
				images.add(path);
			}
		}

		if (schematic != null) {
			this.formSchematic = schematic;
			this.formStatus = schematic.getFileName() + " selected.";
			this.parseSchematicStats(schematic);
		}

		if (!images.isEmpty()) {
			List<Path> combined = new ArrayList<>(this.formPictures);
			combined.addAll(images);
			this.applyPictures(combined);
		}

		if (schematic == null && images.isEmpty()) {
			this.formStatus = "Drop a .litematic file or PNG/JPG images.";
		}
	}

	private boolean anyFormFieldFocused() {
		return (this.titleBox != null && this.titleBox.isFocused())
				|| (this.thumbnailBox != null && this.thumbnailBox.isFocused())
				|| (this.designerBox != null && this.designerBox.isFocused())
				|| (this.descriptionBox != null && this.descriptionBox.isFocused())
				|| (this.codeBox != null && this.codeBox.isFocused());
	}

	private void pasteFromClipboard() {
		if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
			this.formStatus = "Clipboard paste is not supported on macOS.";
			return;
		}

		this.formStatus = "Reading clipboard...";

		Thread worker = new Thread(() -> {
			Path pastedImage = null;
			String pastedUrl = null;

			try {
				System.setProperty("java.awt.headless", "false");
				java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
				java.awt.datatransfer.Transferable contents = clipboard.getContents(null);

				if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
					java.awt.Image image = (java.awt.Image) contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor);
					pastedImage = this.writePastedPng(toBufferedImage(image));
				} else if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
					String text = ((String) contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor)).trim();

					if (text.startsWith("http://") || text.startsWith("https://")) {
						pastedUrl = text.split("\\s+")[0];
					}
				} else if (contents != null) {
					StringBuilder flavors = new StringBuilder();

					for (java.awt.datatransfer.DataFlavor flavor : contents.getTransferDataFlavors()) {
						flavors.append(flavor.getMimeType()).append(" | ");
					}

					SchematicIndexMod.LOGGER.warn("Clipboard had no image/text. Flavors: {}", flavors);
				}
			} catch (Throwable e) {
				SchematicIndexMod.LOGGER.warn("Clipboard read failed", e);
			}

			Path downloaded = null;
			boolean downloadedSchematic = false;
			boolean unsupportedLink = false;

			if (pastedUrl != null) {
				String extension = urlExtension(pastedUrl);
				boolean isSchematic = extension.equals("litematic");
				boolean isImage = extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg");

				if (isSchematic || isImage) {
					try {
						Path target = this.pastedFile(isSchematic ? "litematic" : extension);

						if (Backend.download(pastedUrl, target)) {
							downloaded = target;
							downloadedSchematic = isSchematic;
						}
					} catch (Throwable e) {
						SchematicIndexMod.LOGGER.warn("Clipboard link download failed", e);
					}
				} else {
					unsupportedLink = true;
				}
			}

			Path image = pastedImage;
			Path file = downloaded;
			boolean asSchematic = downloadedSchematic;
			boolean hadUrl = pastedUrl != null;
			boolean badType = unsupportedLink;
			Minecraft.getInstance().execute(() -> {
				if (image != null) {
					this.addFormPicture(image);
					this.formStatus = "Pasted image added.";
				} else if (file != null && asSchematic) {
					this.formSchematic = file;
					this.formStatus = file.getFileName() + " selected.";
					this.parseSchematicStats(file);
				} else if (file != null) {
					this.addFormPicture(file);
					this.formStatus = "Downloaded image added.";
				} else if (badType) {
					this.formStatus = "That link isn't a .litematic or image.";
				} else if (hadUrl) {
					this.formStatus = "Could not fetch that link - Discord links expire, copy a fresh one.";
				} else {
					this.formStatus = "No image or link on the clipboard.";
				}
			});
		}, "schematicindex-paste");
		worker.setDaemon(true);
		worker.start();
	}

	private void addFormPicture(Path path) {
		List<Path> combined = new ArrayList<>(this.formPictures);
		combined.add(path);
		this.applyPictures(combined);
	}

	private Path writePastedPng(java.awt.image.BufferedImage image) throws java.io.IOException {
		Path file = this.pastedFile("png");
		javax.imageio.ImageIO.write(image, "png", file.toFile());
		return file;
	}

	private Path pastedFile(String extension) throws java.io.IOException {
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
				.resolve(SchematicIndexMod.MOD_ID).resolve("pasted");
		Files.createDirectories(dir);
		return dir.resolve("paste-" + System.currentTimeMillis() + "." + extension);
	}

	private static String urlExtension(String url) {
		String path = url.split("[?#]")[0];
		int dot = path.lastIndexOf('.');
		int slash = path.lastIndexOf('/');
		return dot > slash && dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
	}

	private static java.awt.image.BufferedImage toBufferedImage(java.awt.Image image) {
		if (image instanceof java.awt.image.BufferedImage buffered) {
			return buffered;
		}

		int width = Math.max(1, image.getWidth(null));
		int height = Math.max(1, image.getHeight(null));
		java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(width, height,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D graphics = buffered.createGraphics();
		graphics.drawImage(image, 0, 0, null);
		graphics.dispose();
		return buffered;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// Arrow keys move focus between the buttons of the active confirmation/input modal;
		// Enter activates the focused one. Other keys fall through to the per-modal handlers.
		Rect[] modalButtons = this.modalFocusButtons();

		if (modalButtons != null) {
			switch (event.key()) {
				case 262, 264 -> { // right / down
					this.modalFocus = this.modalFocus < 0 ? 0 : (this.modalFocus + 1) % modalButtons.length;
					Theme.click();
					return true;
				}
				case 263, 265 -> { // left / up
					this.modalFocus = this.modalFocus < 0
							? 0
							: (this.modalFocus + modalButtons.length - 1) % modalButtons.length;
					Theme.click();
					return true;
				}
				case 257, 335 -> { // enter
					this.activateModalFocus();
					return true;
				}
				default -> {
				}
			}
		}

		if (this.editPostOpen) {
			if (event.key() == 256) {
				this.editPostOpen = false;
				this.editPostId = null;
				this.setFocused(null);
				return true;
			}

			// Let the focused edit field handle typing, backspace and cursor keys.
			return super.keyPressed(event);
		}

		if (this.postOptionsOpen) {
			if (event.key() == 256) {
				this.postOptionsOpen = false;
			}

			return true;
		}

		if (this.unpublishOpen) {
			if (event.key() == 256) {
				this.unpublishOpen = false;
				this.unpublishId = null;
			}

			return true;
		}

		if (this.errorOpen) {
			if (event.key() == 256) {
				this.errorOpen = false;
			}

			return true;
		}

		if (this.nameInputOpen) {
			switch (event.key()) {
				case 259 -> {
					if (!this.nameInput.isEmpty()) {
						this.nameInput = this.nameInput.substring(0, this.nameInput.length() - 1);
					}
				}
				case 257, 335 -> this.confirmNameInput();
				case 256 -> {
					this.nameInputOpen = false;
					this.namePendingPost = null;
				}
				default -> {
				}
			}

			return true;
		}

		if (this.renameCollectionOpen) {
			switch (event.key()) {
				case 259 -> {
					if (!this.renameInput.isEmpty()) {
						this.renameInput = this.renameInput.substring(0, this.renameInput.length() - 1);
					}
				}
				case 257, 335 -> this.confirmRename();
				case 256 -> {
					this.renameCollectionOpen = false;
					this.renameCollectionFrom = null;
				}
				default -> {
				}
			}

			return true;
		}

		if (this.duplicateOpen) {
			if (event.key() == 256 || event.key() == 257 || event.key() == 335) {
				this.duplicateOpen = false;
			}

			return true;
		}

		if (this.loadCodeOpen) {
			if (event.key() == 86 && (event.modifiers() & 0x2) != 0) {
				this.pasteLoadCode();
				return true;
			}

			switch (event.key()) {
				case 259 -> {
					if (!this.loadCodeInput.isEmpty()) {
						this.loadCodeInput = this.loadCodeInput.substring(0, this.loadCodeInput.length() - 1);
					}
				}
				case 257, 335 -> this.confirmLoadCode();
				case 256 -> this.loadCodeOpen = false;
				default -> {
				}
			}

			return true;
		}

		if (this.collectionOptionsOpen) {
			if (event.key() == 256) {
				this.collectionOptionsOpen = false;
				this.collectionOptionsName = null;
			}

			return true;
		}

		if (this.deleteCollectionOpen) {
			if (event.key() == 256) {
				this.deleteCollectionOpen = false;
				this.deleteCollectionName = null;
			}

			return true;
		}

		if (this.tosOpen) {
			if (event.key() == 256 && this.tosScrolledBottom) {
				Settings.revokeTerms();
				this.onClose();
			}

			return true;
		}

		if (this.tutorialActive) {
			if (event.key() == 256) {
				this.finishTutorial();
			}

			return true;
		}

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
			if (this.collectionMenuOpen) {
				this.collectionMenuOpen = false;
				return true;
			}

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

		if (event.key() == 298 && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) {
			this.showError("SI-TEST99");
			return true;
		}

		if (this.page == Page.UPLOAD && this.uploadFormOpen && event.key() == 86
				&& (event.modifiers() & 0x2) != 0 && !this.anyFormFieldFocused()) {
			this.pasteFromClipboard();
			return true;
		}

		if (this.gridPage() && this.detail == null && !this.errorOpen && !this.tosOpen
				&& !this.tutorialActive && !this.designerWarnOpen && !this.searchBox.isFocused()
				&& this.handleGridKey(event.key())) {
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		sessionSort = this.sort;
		sessionCategory = this.category;
		sessionScroll = this.scroll;
		sessionShown = this.shownCount;
		this.saveDraft();
		this.flushPendingRate(true);

		ImageStore.releaseAll();
		SchematicPreview.releaseAll();

		if (this.minecraft != null) {
			this.minecraft.setScreen(this.parent);
		}
	}

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
