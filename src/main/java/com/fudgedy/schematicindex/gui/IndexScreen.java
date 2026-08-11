package com.fudgedy.schematicindex.gui;

import com.fudgedy.schematicindex.SchematicIndexMod;
import com.fudgedy.schematicindex.catalogue.Category;
import com.fudgedy.schematicindex.catalogue.MockCatalogue;
import com.fudgedy.schematicindex.catalogue.SchematicEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The catalogue browser. Layout only - entries come from {@link MockCatalogue} and images from
 * {@link ImageStore}, so the whole screen works with no backend.
 */
public class IndexScreen extends Screen {
	private static final int OUTER_MARGIN = 8;
	private static final int CONTENT_MAX_WIDTH = 720;
	private static final int TOP_BAR_HEIGHT = 32;
	private static final int RAIL_WIDTH = 30;
	private static final int RAIL_ITEM_HEIGHT = 28;
	private static final int GUTTER = 6;
	private static final int CAPTION_HEIGHT = 28;
	private static final int CHIP_HEIGHT = 14;
	private static final int CHIP_GAP = 4;
	private static final int SCROLL_STEP = 24;
	private static final int HEART_SIZE = 9;
	private static final int FIELD_HEIGHT = 16;

	/** Client-side only until there is a backend to post likes to. */
	private static final Set<String> LIKED = new HashSet<>();

	private enum Page {
		BROWSE("Browse"),
		SAVED("Saved"),
		UPLOAD("Upload");

		private final String label;

		Page(String label) {
			this.label = label;
		}

		ItemStack icon() {
			return switch (this) {
				case BROWSE -> new ItemStack(Items.MAP);
				case SAVED -> new ItemStack(Items.CHEST);
				case UPLOAD -> new ItemStack(Items.WRITABLE_BOOK);
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
	private EditBox designerBox;
	private EditBox descriptionBox;

	private final Rect closeButton = new Rect();
	private final Rect sortButton = new Rect();
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
	private final List<Path> formPictures = new ArrayList<>();
	private int formPictureStart = -1;
	private int formPicturePreview;
	private final Rect postButton = new Rect();
	private Category formCategory = Category.FARMS;
	private String formStatus = "";

	private @Nullable SchematicEntry detail;
	private int detailImage;
	private boolean detailModel;
	private int detailRotation;
	private double rotateDrag;
	private final Rect detailImageRect = new Rect();
	private final Rect detailPrev = new Rect();
	private final Rect detailNext = new Rect();
	private final Rect detailDownload = new Rect();
	private final Rect detailPreview3d = new Rect();
	private final Rect detailClose = new Rect();
	private final Rect detailHeart = new Rect();
	private String status = "";

	public IndexScreen(@Nullable Screen parent) {
		super(Component.literal("The Schematic Index"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ImageStore.discover();
		SchematicPreview.discover();

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

		for (int i = 0; i < Page.values().length; i++) {
			Rect rect = new Rect();
			rect.set(0, TOP_BAR_HEIGHT + 6 + i * RAIL_ITEM_HEIGHT, RAIL_WIDTH, RAIL_ITEM_HEIGHT);
			this.railRects.add(rect);
		}

		this.buildUploadFields();
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
			case NEWEST -> Comparator.comparingInt(entry -> entry.uploaded().length());
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
		return entry.downloaded() || LIKED.contains(entry.id());
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

		if (titleRoom >= this.font.width(lead + tail) * 2) {
			Theme.textScaled(ctx, this.font, lead, titleX, 7, 2.0F, Theme.TEXT);
			Theme.textScaled(ctx, this.font, tail, titleX + this.font.width(lead) * 2, 7, 2.0F, Theme.ACCENT_BRIGHT);
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

			ctx.renderItem(pages[i].icon(), rect.x + (RAIL_WIDTH - 17) / 2, rect.y + (RAIL_ITEM_HEIGHT - 16) / 2);

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
		if (this.visible.isEmpty()) {
			String message = this.page == Page.SAVED
					? "Nothing saved yet - like or download a post"
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
		Theme.heart(ctx, heart.x, heart.y, LIKED.contains(entry.id()));

		if (entry.downloaded()) {
			int badge = this.font.width("Saved") + 8;
			int badgeX = x + this.cardWidth - badge - 4;
			Theme.roundedRect(ctx, badgeX - 1, y + 3, badge + 2, 13, Theme.RADIUS_PILL, 0xFF000000);
			Theme.roundedRect(ctx, badgeX, y + 4, badge, 11, Theme.RADIUS_PILL, Theme.ACCENT);
			Theme.text(ctx, this.font, "Saved", badgeX + 4, y + 6, Theme.ON_ACCENT);
		}

		int textX = x + 5;
		int textWidth = this.cardWidth - 10;
		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, entry.title(), textWidth)),
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
		y += 18;

		Theme.text(ctx, this.font, "Schematic name", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 2;
		this.field(ctx, this.titleBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 6;

		Theme.text(ctx, this.font, "Designed by", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 2;
		this.field(ctx, this.designerBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 6;

		Theme.text(ctx, this.font, "Description", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 2;
		this.field(ctx, this.descriptionBox, formX, y, formWidth, mouseX, mouseY, partialTick);
		y += FIELD_HEIGHT + 8;

		this.formCategoryButton.set(formX, y, formWidth, FIELD_HEIGHT);
		this.pillButton(ctx, this.formCategoryButton, "Category: " + this.formCategory.label(), mouseX, mouseY, false);
		y += FIELD_HEIGHT + 8;

		// Image picker. Stands in for the web upload form's file picker plus crop step.
		Theme.text(ctx, this.font, "Pictures", formX, y, Theme.TEXT_ASH);
		y += this.font.lineHeight + 2;

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

	// ------------------------------------------------------------------ detail modal

	private void renderDetail(GuiGraphics ctx, int mouseX, int mouseY) {
		SchematicEntry entry = this.detail;

		if (entry == null) {
			return;
		}

		ctx.fill(0, 0, this.width, this.height, Theme.SCRIM);

		int modalWidth = Math.min(this.contentWidth, 440);
		int modalHeight = Math.min(this.height - 24, 232);
		int x = (this.width - modalWidth) / 2;
		int y = (this.height - modalHeight) / 2;
		int pad = 10;

		Theme.roundedRect(ctx, x, y, modalWidth, modalHeight, Theme.RADIUS_MODAL, Theme.SURFACE_ELEVATED);

		int imageWidth = Math.round((modalWidth - pad * 3) * 0.56F);
		int imageHeight = imageHeight(imageWidth);

		if (this.detailModel) {
			Identifier model = SchematicPreview.texture(entry.imageStart(), this.detailRotation);

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
				? Theme.clip(this.font, SchematicPreview.name(entry.imageStart()), imageWidth - 16)
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

		Theme.text(ctx, this.font, Theme.bold(Theme.clip(this.font, entry.title(), infoWidth)), infoX, line, Theme.TEXT);
		line += this.font.lineHeight + 4;
		Theme.text(ctx, this.font, Theme.clip(this.font, "Posted by " + entry.poster(), infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 1;
		Theme.text(ctx, this.font, Theme.clip(this.font, "Designed by " + entry.designer(), infoWidth), infoX, line, Theme.TEXT_MUTE);
		line += this.font.lineHeight + 6;

		line = this.metaRow(ctx, "Size", entry.dimensionsLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Blocks", entry.blockCountLabel(), infoX, line, infoWidth);
		line = this.metaRow(ctx, "Downloads", entry.downloadsLabel(), infoX, line, infoWidth);

		boolean liked = LIKED.contains(entry.id());
		String likeCount = SchematicEntry.compact(likesOf(entry));
		Theme.text(ctx, this.font, "Likes", infoX, line, Theme.TEXT_ASH);
		int likeWidth = this.font.width(likeCount);
		Theme.text(ctx, this.font, likeCount, infoX + infoWidth - likeWidth, line, liked ? Theme.ACCENT_BRIGHT : Theme.TEXT);
		this.detailHeart.set(infoX + infoWidth - likeWidth - HEART_SIZE - 4, line - 1, HEART_SIZE, HEART_SIZE);
		Theme.heart(ctx, this.detailHeart.x, this.detailHeart.y, liked);

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
		int downloadWidth = this.font.width(Theme.bold("Download")) + 18;
		String previewLabel = this.detailModel ? "Pictures" : "3D preview";
		int previewWidth = this.font.width(Theme.bold(previewLabel)) + 18;
		int closeWidth = this.font.width(Theme.bold("Close")) + 18;

		this.detailClose.set(x + pad, buttonY, closeWidth, 16);
		this.detailDownload.set(x + modalWidth - pad - downloadWidth, buttonY, downloadWidth, 16);
		this.detailPreview3d.set(this.detailDownload.x - 6 - previewWidth, buttonY, previewWidth, 16);

		this.pillButton(ctx, this.detailClose, "Close", mouseX, mouseY, false);
		this.pillButton(ctx, this.detailPreview3d, previewLabel, mouseX, mouseY, false);
		this.pillButton(ctx, this.detailDownload, "Download", mouseX, mouseY, true);
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

		if (this.sortButton.contains(mouseX, mouseY)) {
			this.sort = this.sort.next();
			this.layoutChips();
			this.refilter();
			return true;
		}

		for (int i = 0; i < this.chipRects.size(); i++) {
			if (this.chipRects.get(i).contains(mouseX, mouseY)) {
				this.category = this.chipOrder.get(i);
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
				this.detailImage = 0;
				this.detailModel = false;
				this.detailRotation = 0;
				this.status = "";
			}

			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private void switchPage(Page target) {
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

		if (this.postButton.contains(mouseX, mouseY)) {
			this.submitPost();
			return true;
		}

		return this.focusField(this.titleBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.designerBox, event, doubleClick, mouseX, mouseY)
				|| this.focusField(this.descriptionBox, event, doubleClick, mouseX, mouseY);
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

		if (this.formPictures.isEmpty()) {
			this.formStatus = "Select at least one picture.";
			return;
		}

		String designer = this.designerBox.getValue().trim();
		String description = this.descriptionBox.getValue().trim();
		int size = 12 + Math.abs(title.hashCode() % 30);

		SchematicEntry entry = new SchematicEntry(
				MockCatalogue.nextPostId(),
				title,
				String.valueOf(UploaderAccess.profile()),
				designer.isEmpty() ? "unknown" : designer,
				this.formCategory,
				size, 8 + size / 3, size,
				size * size * 2,
				0,
				0,
				"just now",
				description.isEmpty() ? "No description provided." : description,
				this.formPictures.size(),
				this.formPictureStart,
				false
		);

		MockCatalogue.post(entry);
		this.formPictures.clear();
		this.formPictureStart = -1;
		this.formPicturePreview = 0;
		this.titleBox.setValue("");
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
			this.detail = null;
			this.status = "";
		} else if (this.detailHeart.contains(mouseX, mouseY)) {
			toggleLike(entry);
		} else if (this.detailPrev.contains(mouseX, mouseY)) {
			if (this.detailModel) {
				this.detailRotation = Math.floorMod(this.detailRotation - 1, 4);
			} else {
				this.detailImage = Math.floorMod(this.detailImage - 1, entry.imageCount());
			}
		} else if (this.detailNext.contains(mouseX, mouseY)) {
			if (this.detailModel) {
				this.detailRotation = Math.floorMod(this.detailRotation + 1, 4);
			} else {
				this.detailImage = Math.floorMod(this.detailImage + 1, entry.imageCount());
			}
		} else if (this.detailDownload.contains(mouseX, mouseY)) {
			this.status = "No catalogue server configured yet.";
		} else if (this.detailPreview3d.contains(mouseX, mouseY)) {
			this.detailModel = !this.detailModel;
			this.rotateDrag = 0.0D;
			this.status = this.detailModel ? "Drag the preview to rotate." : "";
		}
	}

	private static void toggleLike(SchematicEntry entry) {
		if (!LIKED.remove(entry.id())) {
			LIKED.add(entry.id());
		}
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
		if (this.detail != null && this.detailModel && this.detailImageRect.contains(event.x(), event.y())) {
			this.rotateDrag += dragX;

			while (this.rotateDrag >= 45.0D) {
				this.detailRotation = Math.floorMod(this.detailRotation + 1, 4);
				this.rotateDrag -= 45.0D;
			}

			while (this.rotateDrag <= -45.0D) {
				this.detailRotation = Math.floorMod(this.detailRotation - 1, 4);
				this.rotateDrag += 45.0D;
			}

			return true;
		}

		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.detail != null || this.page == Page.UPLOAD) {
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
