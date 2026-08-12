package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.Settings;
import com.fudgedy.schematicindex.catalogue.Catalogue;
import com.fudgedy.schematicindex.catalogue.Category;
import com.fudgedy.schematicindex.catalogue.Download;
import com.fudgedy.schematicindex.catalogue.MockCatalogue;
import com.fudgedy.schematicindex.catalogue.SchematicEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
	private static final int RAIL_ITEM_GAP = 8;
	private static final int GUTTER = 6;
	private static final int CAPTION_HEIGHT = 28;
	private static final int CHIP_HEIGHT = 14;
	private static final int CHIP_GAP = 4;
	private static final int SCROLL_STEP = 24;
	private static final int HEART_SIZE = 9;
	private static final int FIELD_HEIGHT = 16;

	/** Client-side only until there is a backend to post likes to. */
	private static final Set<String> LIKED = new HashSet<>();

	/** Posts the user chose to keep. This is what the Saved tab lists - not likes, not downloads. */
	private static final Set<String> SAVED_POSTS = new HashSet<>();

	/** When each post was last liked, so the heart can play its pop. */
	private static final Map<String, Long> LIKE_POPS = new HashMap<>();

	private enum Page {
		BROWSE("Browse"),
		SAVED("Saved"),
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
				case UPLOAD -> new ItemStack(Items.WRITABLE_BOOK);
				case SETTINGS -> new ItemStack(Items.ANVIL);
			};
		}
	}

	private final @Nullable Screen parent;
	private final List<SchematicEntry> visible = new ArrayList<>();

	private Page page = Page.BROWSE;
	private Category category = Category.ALL;
	private MockCatalogue.Sort sort = MockCatalogue.Sort.NEWEST;
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
	private final Rect detailClose = new Rect();
	private final Rect detailHeart = new Rect();
	/** 1 = every layer shown; lower values hide the top of the build so interiors can be read. */
	private float detailLayer = 1.0F;
	private boolean draggingLayer;
	private String status = "";

	// Settings page controls
	private final Rect soundsToggle = new Rect();
	private final Rect autoLoadToggle = new Rect();
	private final Rect overwriteToggle = new Rect();
	private final Rect openFolderButton = new Rect();

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

		this.columns = columnsFor(this.contentWidth);
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
		this.gridTop = TOP_BAR_HEIGHT + this.chipRowHeight;
		this.gridBottom = this.height - OUTER_MARGIN;
		this.refilter();
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

		if (this.page == Page.UPLOAD) {
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

		for (SchematicEntry entry : MockCatalogue.entries()) {
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

		int rows = (this.visible.size() + this.columns - 1) / this.columns;
		int contentHeight = rows * this.cardHeight + Math.max(0, rows - 1) * GUTTER;
		this.maxScroll = Math.max(0.0F, contentHeight - (this.gridBottom - this.gridTop));
		this.scroll = Math.min(this.scroll, this.maxScroll);
	}

	private static boolean isSaved(SchematicEntry entry) {
		return SAVED_POSTS.contains(entry.id());
	}

	private static void toggleSaved(SchematicEntry entry) {
		if (SAVED_POSTS.remove(entry.id())) {
			Theme.click(0.9F);
			return;
		}

		SAVED_POSTS.add(entry.id());
		Theme.click(1.3F);
	}

	private static int likesOf(SchematicEntry entry) {
		return entry.likes() + (LIKED.contains(entry.id()) ? 1 : 0);
	}

	private static int imageSlot(SchematicEntry entry, int offset) {
		return entry.imageStart() + offset;
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
		} else {
			this.renderGrid(ctx, hoverX, hoverY);
			this.renderScrollbar(ctx);
			this.renderChipRow(ctx, hoverX, hoverY);
		}

		this.renderRail(ctx, hoverX, hoverY);
		this.renderTopBar(ctx, hoverX, hoverY);
		this.searchBox.render(ctx, mouseX, mouseY, partialTick);

		if (modalOpen) {
			this.renderDetail(ctx, mouseX, mouseY);
		}
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

		int searchY = (TOP_BAR_HEIGHT - 16) / 2;
		boolean focused = this.searchBox.isFocused();
		Theme.roundedRect(ctx, searchX, searchY, searchWidth, 16, Theme.RADIUS_PILL,
				focused ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);

		if (focused) {
			Theme.roundedOutline(ctx, searchX, searchY, searchWidth, 16, Theme.RADIUS_PILL, Theme.ACCENT);
		}

		this.pillButton(ctx, this.closeButton, "Close", mouseX, mouseY, false);
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
			// A lighter tile behind each icon so they read as buttons rather than floating items.
			Theme.roundedRect(ctx, iconX - 2, iconY - 2, 20, 20, Theme.RADIUS_PILL,
					active ? Theme.RAIL_TILE_ACTIVE : Theme.RAIL_TILE);
			ctx.renderItem(pages[i].icon(), iconX, iconY);

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
		int fill = primary
				? (hovered ? Theme.ACCENT_PRESSED : Theme.ACCENT)
				: (hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);

		if (hovered && !primary) {
			Theme.roundedOutline(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);
		}

		String text = Theme.bold(Theme.clip(this.font, label, rect.width - 8));
		Theme.text(ctx, this.font, text,
				rect.x + (rect.width - this.font.width(text)) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1,
				primary ? Theme.ON_ACCENT : Theme.TEXT);
	}

	/** A labelled on/off row: filled accent square when on, hollow when off. */
	private void toggle(GuiGraphics ctx, Rect rect, String label, boolean on, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);

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
		String label = saved ? "Saved" : "Save for later";

		if (saved) {
			Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
					hovered ? Theme.ACCENT_PRESSED : Theme.ACCENT);
			Theme.text(ctx, this.font, Theme.bold(label),
					rect.x + (rect.width - this.font.width(Theme.bold(label))) / 2,
					rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.ON_ACCENT);
			return;
		}

		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				hovered ? Theme.SURFACE_ELEVATED : Theme.SURFACE_CARD);

		if (hovered) {
			Theme.roundedOutline(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, Theme.ACCENT_BRIGHT);
		}

		Theme.text(ctx, this.font, Theme.bold(label),
				rect.x + (rect.width - this.font.width(Theme.bold(label))) / 2,
				rect.y + (rect.height - this.font.lineHeight) / 2 + 1, Theme.TEXT);
	}

	/**
	 * The Download button doubles as its own progress bar: a lighter green grows left to right over
	 * the accent fill as real bytes land, and the label counts up. Polled every frame from
	 * {@link Download}, so it tracks the actual transfer rather than playing a fixed animation.
	 */
	private void downloadButton(GuiGraphics ctx, Rect rect, SchematicEntry entry, int mouseX, int mouseY) {
		boolean hovered = rect.contains(mouseX, mouseY);
		Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL,
				hovered ? Theme.ACCENT_PRESSED : Theme.ACCENT);

		Download.Progress progress = Download.progress(entry.id());
		String label = "Download";

		if (progress != null) {
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

			Theme.roundedRect(ctx, rect.x, rect.y, rect.width, rect.height, Theme.RADIUS_PILL, fill);
			Theme.text(ctx, this.font, Theme.bold(value.label()), rect.x + 6,
					rect.y + (CHIP_HEIGHT - this.font.lineHeight) / 2 + 1,
					active ? Theme.ON_ACCENT : Theme.TEXT_MUTE);
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

		ctx.enableScissor(this.contentX, this.gridTop, this.contentX + this.contentWidth, this.gridBottom);

		int rowHeight = this.cardHeight + GUTTER;
		int firstRow = Math.max(0, (int) (this.scroll / rowHeight));
		int lastRow = Math.min((this.visible.size() - 1) / this.columns,
				(int) ((this.scroll + (this.gridBottom - this.gridTop)) / rowHeight));

		for (int row = firstRow; row <= lastRow; row++) {
			for (int column = 0; column < this.columns; column++) {
				int index = row * this.columns + column;

				if (index >= this.visible.size()) {
					break;
				}

				int x = this.contentX + column * (this.cardWidth + GUTTER);
				int y = this.gridTop + row * rowHeight - Math.round(this.scroll);
				this.renderCard(ctx, this.visible.get(index), x, y, mouseX, mouseY);
			}
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
		Identifier texture = ImageStore.texture(imageSlot(entry, 0));

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
		Theme.heartPopped(ctx, heart.x, heart.y, LIKED.contains(entry.id()), popAge(entry));

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
				"Button clicks and the like chime.", Settings.sounds(), formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.autoLoadToggle, "Load into Litematica after download",
				"Open a schematic as soon as it finishes downloading.", Settings.autoLoad(),
				formX, y, formWidth, mouseX, mouseY);
		y = this.settingRow(ctx, this.overwriteToggle, "Confirm before overwriting",
				"Ask first when a file of the same name already exists.", Settings.confirmOverwrite(),
				formX, y, formWidth, mouseX, mouseY);

		y += 6;
		Theme.text(ctx, this.font, Theme.bold("Download folder"), formX, y, Theme.TEXT);
		y += this.font.lineHeight + 3;

		for (String row : this.wrap(Settings.downloadDirectory().toString(), formWidth, 2)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_MUTE);
			y += this.font.lineHeight + 1;
		}

		y += 3;
		int openWidth = this.font.width(Theme.bold("Open folder")) + 16;
		this.openFolderButton.set(formX, y, openWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.openFolderButton, "Open folder", mouseX, mouseY, false);
		y += FIELD_HEIGHT + 8;

		for (String row : this.wrap("Downloads follow the Minecraft session you launched, so files always land "
				+ "in the profile you are actually playing - there is no path to keep in sync.", formWidth, 3)) {
			Theme.text(ctx, this.font, row, formX, y, Theme.TEXT_ASH);
			y += this.font.lineHeight + 1;
		}
	}

	private int settingRow(GuiGraphics ctx, Rect rect, String label, String hint, boolean on,
			int x, int y, int width, int mouseX, int mouseY) {
		rect.set(x, y, width, FIELD_HEIGHT);
		this.toggle(ctx, rect, label, on, mouseX, mouseY);
		y += FIELD_HEIGHT + 3;
		Theme.text(ctx, this.font, hint, x, y, Theme.TEXT_ASH);
		return y + this.font.lineHeight + 9;
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
			Identifier texture = ImageStore.texture(imageSlot(entry, this.detailImage));

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
		Theme.text(ctx, this.font, Theme.clip(this.font, "Posted by " + entry.poster(), infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 1;
		Theme.text(ctx, this.font, Theme.clip(this.font, "Designed by " + entry.designer(), infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 6;

		line = this.metaRow(ctx, "Dimensions", entry.dimensionsLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Blocks", entry.blockCountLabel(), infoX, line, infoWidth);

		Theme.text(ctx, this.font, "Downloads", infoX, line, Theme.TEXT_ASH);
		String downloads = entry.downloadsLabel();
		int downloadsWidth = this.font.width(downloads);
		Theme.text(ctx, this.font, downloads, infoX + infoWidth - downloadsWidth, line, Theme.TEXT);
		Theme.downloadGlyph(ctx, infoX + infoWidth - downloadsWidth - Theme.DOWNLOAD_GLYPH_WIDTH - 3,
				line + 2, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 2;

		boolean liked = LIKED.contains(entry.id());
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

			// Layer slider: peel off the top of the build to look inside without moving the camera.
			String layerLabel = this.detailLayer >= 1.0F ? "All" : Math.round(this.detailLayer * 100) + "%";
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

		if (this.detail != null) {
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
				Theme.click(1.2F);
				this.detailOpenedAt = System.currentTimeMillis();
				this.detailImage = 0;
				this.detailModel = false;
				this.detailYaw = 35.0F;
				this.detailPitch = 28.0F;
				this.detailZoom = 1.0F;
				this.detailLayer = 1.0F;
				this.status = "";
			}

			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void switchPage(Page target) {
		if (target != this.page) {
			Theme.click(1.1F);
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
				String owner = UploaderAccess.redeem(this.codeBox.getValue());
				this.formStatus = owner == null ? "That code is not valid." : "Unlocked as " + owner + ".";
				this.setFocused(null);
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
		} else if (this.autoLoadToggle.contains(mouseX, mouseY)) {
			Settings.toggleAutoLoad();
			Theme.click(1.1F);
		} else if (this.overwriteToggle.contains(mouseX, mouseY)) {
			Settings.toggleConfirmOverwrite();
			Theme.click(1.1F);
		} else if (this.openFolderButton.contains(mouseX, mouseY)) {
			Theme.click();
			this.openDownloadFolder();
		}

		return true;
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
		if (this.layerSlider.width <= 0) {
			return;
		}

		float fraction = (float) ((mouseX - this.layerSlider.x) / this.layerSlider.width);
		this.detailLayer = Math.max(0.05F, Math.min(1.0F, fraction));
	}

	private void startDownload(SchematicEntry entry) {
		Theme.click(1.2F);
		Path source = SchematicPreview.pathFor(entry.schematicSlot());

		if (source == null) {
			this.status = "No local file to download in this beta.";
			return;
		}

		Download.start(entry.id(), entry.title() + ".litematic", null, source);
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

		String designer = this.designerBox.getValue().trim();
		String description = this.descriptionBox.getValue().trim();
		String thumbnailName = this.thumbnailBox.getValue().trim();
		int size = 12 + Math.abs(title.hashCode() % 30);

		SchematicEntry entry = new SchematicEntry(
				MockCatalogue.nextPostId(),
				title,
				thumbnailName.isEmpty() ? title : thumbnailName,
				String.valueOf(UploaderAccess.profile()),
				designer.isEmpty() ? "unknown" : designer,
				this.formCategory,
				size, 8 + size / 3, size,
				size * size * 2,
				0,
				0,
				System.currentTimeMillis(),
				description.isEmpty() ? "No description provided." : description,
				this.formPictures.size(),
				this.formPictureStart,
				SchematicPreview.register(this.formSchematic),
				false
		);

		MockCatalogue.post(entry);
		this.formPictures.clear();
		this.formPictureStart = -1;
		this.formPicturePreview = 0;
		this.formSchematic = null;
		this.titleBox.setValue("");
		this.thumbnailBox.setValue("");
		this.designerBox.setValue("");
		this.descriptionBox.setValue("");
		this.formStatus = "Posted \"" + title + "\" - beta only, it lives in memory until you quit.";
		this.switchPage(Page.BROWSE);
		this.formStatus = "";
		this.status = "";
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

		if (this.detailClose.contains(mouseX, mouseY)) {
			Theme.click(0.9F);
			this.detail = null;
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
		} else if (this.detailDownload.contains(mouseX, mouseY)) {
			this.startDownload(entry);
		} else if (this.detailPreview3d.contains(mouseX, mouseY)) {
			Theme.click(1.2F);
			this.detailModel = !this.detailModel;
			this.status = this.detailModel ? "Drag to orbit, scroll to zoom." : "";
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
		if (LIKED.remove(entry.id())) {
			Theme.click(0.9F);
			return;
		}

		LIKED.add(entry.id());
		LIKE_POPS.put(entry.id(), System.currentTimeMillis());
		Theme.click(1.4F);
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
		return index >= 0 && index < this.visible.size() ? this.visible.get(index) : null;
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

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.detail != null) {
			// Look-around mode is a fixed viewpoint: turning is allowed, moving is not.
			if (this.detailModel && !this.freeLook && this.detailImageRect.contains(mouseX, mouseY)) {
				float factor = scrollY > 0 ? 1.18F : 1.0F / 1.18F;
				this.detailZoom = SchematicPreview.clampZoom(this.detail.schematicSlot(), this.detailZoom * factor);
			}

			return true;
		}

		if (this.page == Page.UPLOAD) {
			return true;
		}

		this.scroll = Math.max(0.0F, Math.min(this.maxScroll, this.scroll - (float) scrollY * SCROLL_STEP));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == 256 && this.detail != null) {
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
