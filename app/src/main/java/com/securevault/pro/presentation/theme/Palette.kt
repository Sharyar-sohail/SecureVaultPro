package com.securevault.pro.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Rendering grammars the component library draws with. Every shipped theme picks
 * one, so the same card, button or icon tile composes differently per theme.
 */
enum class ThemeDesign(
    val label: String,
    val tagline: String
) {
    GENERATIVE("Generative UI", "Adaptive surfaces that shift with context"),
    AGENTIC("Agentic UX", "Intent-first flow, calm conversational surfaces"),
    LIQUID_GLASS("Liquid Glass", "Refractive frosted panes and soft blur"),
    BENTO("Bento Grid 2.0", "Modular tiles on an auto-resizing grid"),
    NEO_SKEUOMORPHIC("Neo-Skeuomorphism", "Tactile matte embossing and bevels"),
    NEO_BRUTALIST("Neo-Brutalism", "Hard outlines, sharp corners, vivid blocks"),
    CLAYMORPHISM("Claymorphism", "Squishy 3D pastel forms"),
    CYBERPUNK("Cyberpunk Glow", "Neon mesh gradients, nocturnal highlights"),
    NEUTRAL("Material You", "Plain tonal surfaces, no brand styling")
}

enum class VaultBackdrop {
    PULSE_MESH,
    AURORA_MESH,
    NEON_MESH,
    CALM,
    FLAT_GRID,
    HARD_GRID,
    SOFT_CLAY,
    TEXTURED
}

enum class VaultLayout {
    /** Four equal columns, every cell the same plate. The reference board. */
    CLASSIC,

    /** Four columns, one double-width lead, a full-width closing bar. */
    BENTO,

    /** Full-width intent rails over a quiet grid of bare icons. */
    AGENTIC,

    /** Two wide columns, the first two modules promoted. */
    ADAPTIVE,

    /** Three columns of thick clay plates. */
    GENERATIVE,

    /** Three columns, a double-width cell beside a single, repeated. */
    MOSAIC,

    /** Two columns of full-width rails: label and hint inline, no plate above. */
    LEDGER,

    /** Two columns of large feature cards, icon beside text. */
    DUO,

    /** Five tight columns of bare icons — the smallest cells the board ships. */
    COMPACT,

    /** Six columns, two rows: the densest board, for big screens. */
    DENSE,

    /** Three columns led by one full-width cell, the rest equal plates. */
    COLUMN,

    /** Two columns alternating a feature card with a plain plate. */
    SPLIT,

    /** Three columns alternating a plate with a double-width feature, mirrored. */
    GALLERY,

    /** Four columns led by a full-width rail, then double-width feature pairs. */
    STRIP,

    /** Four columns of double-width feature cells: two per row. */
    TRIAD
}

/** The Material icon language a theme draws its glyphs in. */
enum class IconStyle {
    ROUNDED,
    OUTLINED,
    FILLED,
    SHARP
}

/** How the bottom dock is built: silhouette, float, frosting. */
enum class DockStyle {
    /** Edge-to-edge minimal bar with a hairline top rule. */
    MINIMAL_BAR,
    /** Rounded bar lifted off the sides — the HyperOS/ColorOS shell. */
    SOFT_FLOATING,
    /** Translucent frosted capsule, iOS-style, with a strong blur pane. */
    PILL_FROSTED,
    /** Tall wide panel with roomy labels — One UI comfort targeting. */
    COMFORT_PANEL,
    /** Square-cut flat plate fused to the bottom edge — XOS/EMUI. */
    FLAT_PLATE
}

/** How the screen header is set: alignment, weight, breathing room. */
enum class HeaderStyle {
    /** Compact left-aligned title over a subtitle (MIUI/ColorOS). */
    INLINE_BOLD,
    /** Centred title, tiny subtitle underneath (iOS). */
    CENTERED,
    /** Oversized headline pushed high with wide side margins (One UI). */
    LARGE_COMFORT,
    /** Uppercase tracked headline with a rule beneath (EMUI/XOS). */
    TAB_UPPER
}

/** The selected-state marker drawn on a dock item. */
enum class NavIndicator {
    PILL,
    UNDERLINE,
    DOT,
    NONE
}

/** How the Vault Composition chart is cut. Each brand ships its own chart idiom. */
enum class PieForm {
    /** Solid wedges meeting at the centre — the flat MIUI / Fluent pie. */
    WEDGE,
    /** One stroked ring, arcs separated by gaps — iOS, One UI, Funtouch. */
    RING,
    /** A band per category, each on its own concentric radius — ColorOS / EMUI. */
    STACKED_RING
}

/** The shape of the legend key beside the chart. */
enum class PieLegend {
    DOT,
    SQUARE,
    DIAMOND,
    BAR,
    RING
}

data class VaultPalette(
    val id: String,
    val name: String,
    val tagline: String,
    val inspiredBy: String,
    val isLight: Boolean = false,
    val abyssDark: Color,
    val abyssSurface: Color,
    val abyssElevated: Color,
    val abyssGlass: Color,
    val cyan: Color,
    val amber: Color,
    val indigo: Color,
    val mint: Color,
    val coral: Color,
    val lavender: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val dangerDim: Color,
    val design: ThemeDesign = ThemeDesign.LIQUID_GLASS,
    val backdrop: VaultBackdrop = VaultBackdrop.AURORA_MESH,
    val layout: VaultLayout = VaultLayout.CLASSIC,
    val iconStyle: IconStyle = IconStyle.ROUNDED,
    val cardCorner: Dp = 22.dp,
    val buttonCorner: Dp = 18.dp,
    val inputCorner: Dp = 16.dp,
    val chipCorner: Dp = 8.dp,
    val navCorner: Dp = 26.dp,
    val cardBorderWidth: Dp = 1.dp,
    val cardFillAlpha: Float = 0.72f,
    val cardGradient: Boolean = true,
    val cardHighlight: Boolean = true,
    val cardShadow: Dp = 0.dp,
    val glow: Boolean = true,
    val meshIntensity: Float = 0.7f,
    val gradientButtons: Boolean = true,
    val hardOutline: Boolean = false,
    val embossed: Boolean = false,
    val navFloating: Boolean = true,
    val uppercaseLabels: Boolean = false,
    val monoTags: Boolean = false,
    /**
     * Icon-plate radius. One shared value for every theme — the generated board draws
     * the same squircle in every palette, so the Kotlin tile must not drift per
     * theme either. No palette may override this; see ThemeTokensTest.
     *
     * The number comes from the board itself: the vectors bake a corner of 60 units in
     * a 256-unit plate (23.4%), and the default tile is 38.dp, so 38 * 60/256 ~= 8.9.dp.
     * A fixed dp cannot track a growing tile, so this is the board's own radius at the
     * size the tiles actually ship at — every call site between 30.dp and 48.dp stays
     * within a hair of the vector plate's silhouette.
     */
    val iconTileCorner: Dp = 9.dp,
    val pressDepth: Dp = 0.dp,
    val backdropBlur: Dp = 0.dp,
    val sheen: Boolean = false,
    val dock: DockStyle = DockStyle.SOFT_FLOATING,
    val header: HeaderStyle = HeaderStyle.INLINE_BOLD,
    val navIndicator: NavIndicator = NavIndicator.PILL,
    val navLabels: Boolean = true,
    /** Alpha of the dock fill; below ~0.85 the wallpaper reads through. */
    val navFrostAlpha: Float = 0.97f,
    val navHeight: Dp = 62.dp,
    val navIconSize: Dp = 21.dp,
    val navLabelSize: TextUnit = 10.sp,
    val screenGutter: Dp = 18.dp,
    /**
     * Home board geometry. These four are the same for every theme on purpose: the
     * icons and their layout have to read identically whichever theme is picked, so
     * only the plate colours move.
     */
    val gridGutter: Dp = 8.dp,
    val moduleTilePadH: Dp = 6.dp,
    val moduleTilePadV: Dp = 11.dp,
    val moduleIconSize: Dp = 34.dp,
    val moduleLabelSize: TextUnit = 10.sp,
    /** How this brand cuts its Vault Composition chart. */
    val pieForm: PieForm = PieForm.RING,
    /** Empty degrees between two arcs; wedge charts use a separator stroke instead. */
    val pieGapDegrees: Float = 3.5f,
    val pieRoundCaps: Boolean = true,
    /** Radial wash across each slice instead of flat fill. */
    val pieGradient: Boolean = false,
    /** Hairline between wedges, in the card surface colour. */
    val pieSeparator: Dp = 0.dp,
    val pieTrackAlpha: Float = 0.55f,
    /** 0 = solid pie, higher = thinner ring. Only read by the ring forms. */
    val pieInnerRatio: Float = 0.62f,
    val pieLegend: PieLegend = PieLegend.DOT,
    /** Selected slice steps outward, the iOS/MIUI affordance. */
    val pieSelectedLift: Boolean = false
) {
    val preview: List<Color>
        get() = listOf(cyan, indigo, mint, amber, lavender, coral)

    val cardShape: RoundedCornerShape get() = RoundedCornerShape(cardCorner)
    val buttonShape: RoundedCornerShape get() = RoundedCornerShape(buttonCorner)
    val inputShape: RoundedCornerShape get() = RoundedCornerShape(inputCorner)
    val chipShape: RoundedCornerShape get() = RoundedCornerShape(chipCorner)
    val navShape: RoundedCornerShape get() = RoundedCornerShape(navCorner)
    val iconShape: RoundedCornerShape get() = RoundedCornerShape(iconTileCorner)
    val outlineColor: Color
        get() = if (hardOutline) textPrimary.copy(alpha = 0.92f) else glassBorder

    /**
     * Overlay ink for dividers, chips and inset panels that sit on a card. Light
     * themes need a darkening tint and dark themes a lifting one, otherwise the
     * same hardcoded white reads as a washed-out ghost box on every light palette.
     */
    val surfaceOverlay: Color
        get() = if (isLight) textPrimary else Color.White

    /** Shadow ink for embossing and vignettes, instead of a flat black. */
    val surfaceShade: Color
        get() = if (isLight) textPrimary else abyssDark

    /**
     * The unfilled ring behind a chart. The brand hairline is tinted and already
     * semi-transparent, so stacking [pieTrackAlpha] on it produced a muddy wash that
     * read as dirt on the dark themes; this lifts or darkens the card surface with a
     * single neutral tint instead.
     */
    val pieTrack: Color
        get() = surfaceOverlay.copy(alpha = (pieTrackAlpha * 0.4f).coerceIn(0.05f, 0.22f))
}

object VaultPalettes {
    // The owner's handset runs ColorOS, and Aqua Tint is the one theme whose layout
    // grammar actually matches it, so an unknown or retired id lands there.
    const val DEFAULT_ID = "aqua"
    const val DEFAULT_LIGHT_ID = "solstice"
    const val DEFAULT_DARK_ID = "dark"

    val all: List<VaultPalette> = listOf(
        // 1 — Solar Pop: warm paper, electric ink blocks, hard outlines, mosaic cells.
        VaultPalette(
            id = "solstice",
            name = "Solar Pop",
            tagline = "Electric orange and violet ink on warm paper, hard outlines, mosaic cells",
            inspiredBy = "Screen-print poster",
            iconStyle = IconStyle.FILLED,
            isLight = true,
            // The poster look lives in the hard outlines and the mosaic board; the design token
            // stays NEUTRAL so the icon plates remain tints instead of solid blocks.
            design = ThemeDesign.NEUTRAL,
            backdrop = VaultBackdrop.HARD_GRID,
            layout = VaultLayout.MOSAIC,
            abyssDark = Color(0xFFFFF6DF),
            abyssSurface = Color(0xFFFFFFFF),
            abyssElevated = Color(0xFFFFE9B8),
            abyssGlass = Color(0xF2FFFFFF),
            cyan = Color(0xFFFF5A00),
            amber = Color(0xFFFFB000),
            indigo = Color(0xFF5B21E8),
            mint = Color(0xFF00B84C),
            coral = Color(0xFFFF1F4E),
            lavender = Color(0xFFD000E0),
            textPrimary = Color(0xFF150E00),
            textSecondary = Color(0xFF4A3A18),
            textMuted = Color(0xFF6E5D34),
            glassBorder = Color(0xE6150E00),
            glassHighlight = Color(0x0A000000),
            dangerDim = Color(0xFFFFD8D8),
            cardCorner = 10.dp,
            buttonCorner = 8.dp,
            inputCorner = 6.dp,
            chipCorner = 4.dp,
            navCorner = 4.dp,
            cardBorderWidth = 2.dp,
            cardFillAlpha = 1f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 0.dp,
            glow = false,
            meshIntensity = 0.3f,
            gradientButtons = true,
            hardOutline = true,
            navFloating = false,
            uppercaseLabels = true,
            monoTags = true,
            pressDepth = 3.dp,
            backdropBlur = 0.dp,
            sheen = false,
            dock = DockStyle.FLAT_PLATE,
            header = HeaderStyle.INLINE_BOLD,
            navIndicator = NavIndicator.UNDERLINE,
            navFrostAlpha = 1f,
            navHeight = 68.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.WEDGE,
            pieGapDegrees = 0f,
            pieRoundCaps = false,
            pieGradient = false,
            pieSeparator = 2.dp,
            pieTrackAlpha = 0.28f,
            pieInnerRatio = 0.88f,
            pieLegend = PieLegend.SQUARE,
            pieSelectedLift = false
        ),
        // 2 — Samsung One UI: oversized headline, wide gutters, tall comfort dock.
        VaultPalette(
            id = "oneui",
            name = "Comfort Panel",
            tagline = "One UI-style large headers, roomy margins, tall labelled dock",
            inspiredBy = "Samsung One UI",
            iconStyle = IconStyle.OUTLINED,
            isLight = true,
            design = ThemeDesign.GENERATIVE,
            backdrop = VaultBackdrop.TEXTURED,
            layout = VaultLayout.ADAPTIVE,
            abyssDark = Color(0xFFF6F7FB),
            abyssSurface = Color(0xFFFFFFFF),
            abyssElevated = Color(0xFFE9ECF5),
            abyssGlass = Color(0xE6FFFFFF),
            cyan = Color(0xFF1B6EF3),
            amber = Color(0xFFE88B00),
            indigo = Color(0xFF6A5BF7),
            mint = Color(0xFF14B87A),
            coral = Color(0xFFE64545),
            lavender = Color(0xFF9C6ADE),
            textPrimary = Color(0xFF0D1017),
            textSecondary = Color(0xFF4B5261),
            textMuted = Color(0xFF868D9C),
            glassBorder = Color(0x140D1017),
            glassHighlight = Color(0x14FFFFFF),
            dangerDim = Color(0xFFFDE3E3),
            cardCorner = 26.dp,
            buttonCorner = 26.dp,
            inputCorner = 20.dp,
            chipCorner = 16.dp,
            navCorner = 30.dp,
            cardBorderWidth = 0.dp,
            cardFillAlpha = 0.98f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 6.dp,
            glow = false,
            gradientButtons = false,
            navFloating = true,
            dock = DockStyle.COMFORT_PANEL,
            header = HeaderStyle.LARGE_COMFORT,
            navIndicator = NavIndicator.UNDERLINE,
            navHeight = 76.dp,
            navIconSize = 24.dp,
            navLabelSize = 11.sp,
            navFrostAlpha = 0.99f,
            pieForm = PieForm.RING,
            pieGapDegrees = 9f,
            pieRoundCaps = false,
            pieTrackAlpha = 0.22f,
            pieInnerRatio = 0.78f,
            pieLegend = PieLegend.BAR
        ),
        // 3 — ColorOS / Oppo: deep ink panes, glossy accent layers, wide radii.
        VaultPalette(
            id = "aqua",
            name = "Aqua Tint",
            tagline = "ColorOS-style accent layers over sleek glass panes",
            inspiredBy = "Oppo ColorOS",
            iconStyle = IconStyle.OUTLINED,
            design = ThemeDesign.LIQUID_GLASS,
            backdrop = VaultBackdrop.AURORA_MESH,
            layout = VaultLayout.COMPACT,
            abyssDark = Color(0xFF04101C),
            abyssSurface = Color(0xFF0A1B2C),
            abyssElevated = Color(0xFF12293F),
            abyssGlass = Color(0x99123049),
            cyan = Color(0xFF2ED3B7),
            amber = Color(0xFFFFB44D),
            indigo = Color(0xFF6C8CFF),
            mint = Color(0xFF5EE6A0),
            coral = Color(0xFFFF6F8B),
            lavender = Color(0xFFB08CFF),
            textPrimary = Color(0xFFF7FCFF),
            textSecondary = Color(0xFFC3DCF0),
            textMuted = Color(0xFF91B2CC),
            glassBorder = Color(0x6633E8C8),
            glassHighlight = Color(0x3DFFFFFF),
            dangerDim = Color(0xFF331420),
            cardCorner = 32.dp,
            buttonCorner = 26.dp,
            inputCorner = 20.dp,
            chipCorner = 16.dp,
            navCorner = 32.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.58f,
            cardShadow = 16.dp,
            meshIntensity = 0.95f,
            backdropBlur = 34.dp,
            sheen = true,
            dock = DockStyle.PILL_FROSTED,
            header = HeaderStyle.CENTERED,
            navIndicator = NavIndicator.PILL,
            navHeight = 64.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            navFrostAlpha = 0.74f,
            pieForm = PieForm.STACKED_RING,
            pieGapDegrees = 2f,
            pieRoundCaps = true,
            pieGradient = true,
            pieTrackAlpha = 0.35f,
            pieInnerRatio = 0.50f,
            pieLegend = PieLegend.RING
        ),
        // 4 — Neon Lime: daylight cyberpunk, lime and cyan on white glass, sharp plates.
        VaultPalette(
            id = "lime",
            name = "Neon Lime",
            tagline = "Daylight lime and cyan neon on white glass with sharp plates",
            inspiredBy = "Arcade daylight",
            isLight = true,
            design = ThemeDesign.CYBERPUNK,
            backdrop = VaultBackdrop.NEON_MESH,
            layout = VaultLayout.CLASSIC,
            iconStyle = IconStyle.SHARP,
            abyssDark = Color(0xFFF3FFF0),
            abyssSurface = Color(0xFFFFFFFF),
            abyssElevated = Color(0xFFE3FFD6),
            abyssGlass = Color(0xE6FFFFFF),
            cyan = Color(0xFF00B894),
            amber = Color(0xFFFFB000),
            indigo = Color(0xFF7C4DFF),
            mint = Color(0xFF00C853),
            coral = Color(0xFFFF1744),
            lavender = Color(0xFFD500F9),
            textPrimary = Color(0xFF07130A),
            textSecondary = Color(0xFF2E4A34),
            textMuted = Color(0xFF52705A),
            glassBorder = Color(0x3300B894),
            glassHighlight = Color(0x30FFFFFF),
            dangerDim = Color(0xFFFFD9DE),
            cardCorner = 4.5.dp,
            buttonCorner = 3.5.dp,
            inputCorner = 3.dp,
            chipCorner = 2.5.dp,
            navCorner = 6.dp,
            cardBorderWidth = 1.5.dp,
            cardFillAlpha = 0.92f,
            cardGradient = true,
            cardHighlight = true,
            cardShadow = 4.dp,
            glow = true,
            meshIntensity = 0.55f,
            gradientButtons = true,
            hardOutline = true,
            navFloating = true,
            uppercaseLabels = true,
            monoTags = true,
            backdropBlur = 8.dp,
            sheen = false,
            dock = DockStyle.SOFT_FLOATING,
            header = HeaderStyle.TAB_UPPER,
            navIndicator = NavIndicator.DOT,
            navFrostAlpha = 0.92f,
            navHeight = 64.dp,
            navIconSize = 21.dp,
            navLabelSize = 9.sp,
            pieForm = PieForm.STACKED_RING,
            pieGapDegrees = 1.5f,
            pieRoundCaps = true,
            pieGradient = true,
            pieTrackAlpha = 0.25f,
            pieInnerRatio = 0.55f,
            pieLegend = PieLegend.RING,
            pieSelectedLift = true
        ),
        // 5 — Midnight Pop: near-black glass with electric accents, high contrast.
        VaultPalette(
            id = "dark",
            name = "Midnight Pop",
            tagline = "Near-black glass with electric accents and high-contrast text",
            inspiredBy = "Night arcade",
            design = ThemeDesign.GENERATIVE,
            backdrop = VaultBackdrop.NEON_MESH,
            layout = VaultLayout.LEDGER,
            iconStyle = IconStyle.OUTLINED,
            abyssDark = Color(0xFF0A0A12),
            abyssSurface = Color(0xFF12121C),
            abyssElevated = Color(0xFF1F1F2E),
            abyssGlass = Color(0xCC171724),
            cyan = Color(0xFF00E5FF),
            amber = Color(0xFFFFC400),
            indigo = Color(0xFF7C6BFF),
            mint = Color(0xFF00FF9D),
            coral = Color(0xFFFF2D6F),
            lavender = Color(0xFFE040FB),
            textPrimary = Color(0xFFF7F5FF),
            textSecondary = Color(0xFFD2CCF0),
            textMuted = Color(0xFFA8A2C8),
            glassBorder = Color(0x24FFFFFF),
            glassHighlight = Color(0x14FFFFFF),
            dangerDim = Color(0xFF3E2E2C),
            cardCorner = 12.dp,
            buttonCorner = 24.dp,
            inputCorner = 12.dp,
            chipCorner = 8.dp,
            navCorner = 0.dp,
            cardBorderWidth = 0.dp,
            cardFillAlpha = 1f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 3.dp,
            glow = false,
            meshIntensity = 0.25f,
            gradientButtons = false,
            navFloating = false,
            dock = DockStyle.MINIMAL_BAR,
            header = HeaderStyle.INLINE_BOLD,
            navIndicator = NavIndicator.PILL,
            navHeight = 74.dp,
            navIconSize = 24.dp,
            navLabelSize = 12.sp,
            navFrostAlpha = 1f,
            pieForm = PieForm.RING,
            pieGapDegrees = 2f,
            pieRoundCaps = false,
            pieTrackAlpha = 0.24f,
            pieInnerRatio = 0.60f,
            pieLegend = PieLegend.DOT
        ),
        // 6 — Obsidian Noir: graphite editorial dark, desaturated champagne gold,
        // minimal bar dock and uppercase tracked headers. Calm, no glow.
        VaultPalette(
            id = "noir",
            name = "Obsidian Noir",
            tagline = "Graphite editorial dark with champagne gold and tracked caps headers",
            inspiredBy = "Print editorial",
            iconStyle = IconStyle.OUTLINED,
            design = ThemeDesign.AGENTIC,
            backdrop = VaultBackdrop.CALM,
            layout = VaultLayout.DENSE,
            abyssDark = Color(0xFF121418),
            abyssSurface = Color(0xFF1A1D22),
            abyssElevated = Color(0xFF242830),
            abyssGlass = Color(0xCC1E2228),
            cyan = Color(0xFFFFC94D),
            amber = Color(0xFFFF9F1C),
            indigo = Color(0xFF6EA8FF),
            mint = Color(0xFF7BE495),
            coral = Color(0xFFFF6B5E),
            lavender = Color(0xFFC9A8FF),
            textPrimary = Color(0xFFFBF8EF),
            textSecondary = Color(0xFFCFC9B8),
            textMuted = Color(0xFF9C9688),
            glassBorder = Color(0x3D8F7A4A),
            glassHighlight = Color(0x1FFFFFFF),
            dangerDim = Color(0xFF38221D),
            cardCorner = 16.dp,
            buttonCorner = 12.dp,
            inputCorner = 12.dp,
            chipCorner = 6.dp,
            navCorner = 0.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.94f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 8.dp,
            glow = false,
            meshIntensity = 0.3f,
            gradientButtons = false,
            navFloating = false,
            uppercaseLabels = true,
            backdropBlur = 0.dp,
            sheen = false,
            dock = DockStyle.MINIMAL_BAR,
            header = HeaderStyle.TAB_UPPER,
            navIndicator = NavIndicator.UNDERLINE,
            navFrostAlpha = 0.98f,
            navHeight = 58.dp,
            navIconSize = 20.dp,
            navLabelSize = 9.sp,
            pieForm = PieForm.WEDGE,
            pieGapDegrees = 0f,
            pieRoundCaps = false,
            pieGradient = false,
            pieSeparator = 1.dp,
            pieTrackAlpha = 0.2f,
            pieInnerRatio = 0.9f,
            pieLegend = PieLegend.SQUARE,
            pieSelectedLift = false
        ),
        // 7 — Mint Ledger: airy light green/teal, bento tiles, soft floating dock,
        // large comfort headers, zero glow.
        VaultPalette(
            id = "mint",
            name = "Mint Ledger",
            tagline = "Airy green/teal light theme with soft bento tiles and comfort headers",
            inspiredBy = "Ledger paper",
            isLight = true,
            iconStyle = IconStyle.ROUNDED,
            design = ThemeDesign.BENTO,
            backdrop = VaultBackdrop.PULSE_MESH,
            layout = VaultLayout.DUO,
            abyssDark = Color(0xFFE8F3EC),
            abyssSurface = Color(0xFFF6FBF7),
            abyssElevated = Color(0xFFDDEBE2),
            abyssGlass = Color(0xA6FFFFFF),
            cyan = Color(0xFF0C7C70),
            amber = Color(0xFFE3A23C),
            indigo = Color(0xFF4B6BCB),
            mint = Color(0xFF2FA36B),
            coral = Color(0xFFD6604A),
            lavender = Color(0xFF8A7FBF),
            textPrimary = Color(0xFF0F2A22),
            textSecondary = Color(0xFF3E5C50),
            textMuted = Color(0xFF6E8B7E),
            glassBorder = Color(0x330C7C70),
            glassHighlight = Color(0x4DFFFFFF),
            dangerDim = Color(0xFFFBE3DE),
            cardCorner = 24.dp,
            buttonCorner = 20.dp,
            inputCorner = 16.dp,
            chipCorner = 12.dp,
            navCorner = 28.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.85f,
            cardGradient = false,
            cardHighlight = true,
            cardShadow = 6.dp,
            glow = false,
            meshIntensity = 0.45f,
            gradientButtons = true,
            navFloating = true,
            backdropBlur = 18.dp,
            sheen = false,
            dock = DockStyle.SOFT_FLOATING,
            header = HeaderStyle.LARGE_COMFORT,
            navIndicator = NavIndicator.PILL,
            navFrostAlpha = 0.9f,
            navHeight = 70.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.RING,
            pieGapDegrees = 6f,
            pieRoundCaps = true,
            pieGradient = true,
            pieTrackAlpha = 0.3f,
            pieInnerRatio = 0.72f,
            pieLegend = PieLegend.DOT,
            pieSelectedLift = false
        ),
        // 8 — Sunset Dusk: warm coral/amber mesh on umber glass, frosted pill dock,
        // gradient cards with a top sheen.
        VaultPalette(
            id = "sunset",
            name = "Sunset Dusk",
            tagline = "Warm coral dusk mesh with sheened gradient cards and a frosted pill dock",
            inspiredBy = "Golden hour",
            iconStyle = IconStyle.ROUNDED,
            design = ThemeDesign.GENERATIVE,
            backdrop = VaultBackdrop.AURORA_MESH,
            layout = VaultLayout.GALLERY,
            abyssDark = Color(0xFF170E12),
            abyssSurface = Color(0xFF2A1A16),
            abyssElevated = Color(0xFF3A241C),
            abyssGlass = Color(0x993A241C),
            cyan = Color(0xFFFF8A5C),
            amber = Color(0xFFFFB35C),
            indigo = Color(0xFF6A5AC0),
            mint = Color(0xFF5BCF9B),
            coral = Color(0xFFFF6E7F),
            lavender = Color(0xFFC99BE0),
            textPrimary = Color(0xFFFFEDE2),
            textSecondary = Color(0xFFD6AFA0),
            textMuted = Color(0xFF9E7E73),
            glassBorder = Color(0x40FF8A5C),
            glassHighlight = Color(0x2FFFFF9A),
            dangerDim = Color(0xFF3B1418),
            cardCorner = 28.dp,
            buttonCorner = 22.dp,
            inputCorner = 18.dp,
            chipCorner = 14.dp,
            navCorner = 30.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.66f,
            cardGradient = true,
            cardHighlight = true,
            cardShadow = 10.dp,
            glow = false,
            meshIntensity = 1f,
            gradientButtons = true,
            navFloating = true,
            backdropBlur = 26.dp,
            sheen = true,
            dock = DockStyle.PILL_FROSTED,
            header = HeaderStyle.INLINE_BOLD,
            navIndicator = NavIndicator.PILL,
            navFrostAlpha = 0.62f,
            navHeight = 64.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.WEDGE,
            pieGapDegrees = 0f,
            pieRoundCaps = false,
            pieGradient = true,
            pieSeparator = 1.dp,
            pieTrackAlpha = 0.35f,
            pieInnerRatio = 0.85f,
            pieLegend = PieLegend.DIAMOND,
            pieSelectedLift = true
        ),
        // 9 — Arctic Frost: near-white ice blue, heavy blur on every frosted pane,
        // centred titles, translucent capsule dock.
        VaultPalette(
            id = "arctic",
            name = "Arctic Frost",
            tagline = "Icy near-white light theme with deep frosted blur and centred titles",
            inspiredBy = "Polar glass",
            isLight = true,
            iconStyle = IconStyle.ROUNDED,
            design = ThemeDesign.LIQUID_GLASS,
            backdrop = VaultBackdrop.PULSE_MESH,
            layout = VaultLayout.TRIAD,
            abyssDark = Color(0xFFEDF2F8),
            abyssSurface = Color(0xFFF8FBFE),
            abyssElevated = Color(0xFFDCE7F2),
            abyssGlass = Color(0xB3FFFFFF),
            cyan = Color(0xFF1E6FD9),
            amber = Color(0xFFD9A648),
            indigo = Color(0xFF5B7FD4),
            mint = Color(0xFF35B87F),
            coral = Color(0xFFE4574C),
            lavender = Color(0xFF8FA6E8),
            textPrimary = Color(0xFF0C1B2A),
            textSecondary = Color(0xFF3D556E),
            textMuted = Color(0xFF6E86A0),
            glassBorder = Color(0x260C1B2A),
            glassHighlight = Color(0x59FFFFFF),
            dangerDim = Color(0xFFFFE1E1),
            cardCorner = 32.dp,
            buttonCorner = 26.dp,
            inputCorner = 22.dp,
            chipCorner = 18.dp,
            navCorner = 36.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.72f,
            cardGradient = true,
            cardHighlight = true,
            cardShadow = 12.dp,
            glow = false,
            meshIntensity = 0.55f,
            gradientButtons = true,
            navFloating = true,
            backdropBlur = 48.dp,
            sheen = true,
            dock = DockStyle.PILL_FROSTED,
            header = HeaderStyle.CENTERED,
            navIndicator = NavIndicator.PILL,
            navFrostAlpha = 0.55f,
            navHeight = 68.dp,
            navIconSize = 23.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.RING,
            pieGapDegrees = 5f,
            pieRoundCaps = true,
            pieGradient = true,
            pieTrackAlpha = 0.28f,
            pieInnerRatio = 0.74f,
            pieLegend = PieLegend.RING,
            pieSelectedLift = true
        ),
        // 10 — Volt Aura: cyberpunk lime/cyan neon on near-black, hard outlines,
        // monospace tags, wedge chart with separators.
        VaultPalette(
            id = "voltra",
            name = "Volt Aura",
            tagline = "Neon lime and cyan on near-black with hard outlines and glow",
            inspiredBy = "Arcade night",
            iconStyle = IconStyle.SHARP,
            design = ThemeDesign.CYBERPUNK,
            backdrop = VaultBackdrop.NEON_MESH,
            layout = VaultLayout.SPLIT,
            abyssDark = Color(0xFF05080A),
            abyssSurface = Color(0xFF0D1316),
            abyssElevated = Color(0xFF17242B),
            abyssGlass = Color(0x99101A1F),
            cyan = Color(0xFFC9F23D),
            amber = Color(0xFFFFD400),
            indigo = Color(0xFF23E3FF),
            mint = Color(0xFF38F2A0),
            coral = Color(0xFFFF3D71),
            lavender = Color(0xFFB14DFF),
            textPrimary = Color(0xFFECFFF4),
            textSecondary = Color(0xFF9FC4B8),
            textMuted = Color(0xFF62827A),
            glassBorder = Color(0x40C9F23D),
            glassHighlight = Color(0x3323E3FF),
            dangerDim = Color(0xFF2A1220),
            cardCorner = 6.dp,
            buttonCorner = 4.dp,
            inputCorner = 4.dp,
            chipCorner = 2.dp,
            navCorner = 10.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.8f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 0.dp,
            glow = true,
            meshIntensity = 1f,
            gradientButtons = true,
            hardOutline = true,
            navFloating = true,
            uppercaseLabels = true,
            monoTags = true,
            backdropBlur = 0.dp,
            sheen = false,
            dock = DockStyle.SOFT_FLOATING,
            header = HeaderStyle.TAB_UPPER,
            navIndicator = NavIndicator.DOT,
            navFrostAlpha = 0.9f,
            navHeight = 64.dp,
            navIconSize = 20.dp,
            navLabelSize = 9.sp,
            pieForm = PieForm.WEDGE,
            pieGapDegrees = 0f,
            pieRoundCaps = false,
            pieGradient = false,
            pieSeparator = 2.dp,
            pieTrackAlpha = 0.4f,
            pieInnerRatio = 0.9f,
            pieLegend = PieLegend.SQUARE,
            pieSelectedLift = false
        ),
        // 11 — Terra Clay: claymorphic terracotta and sage pastels, thick soft
        // shadows, embossed comfort surfaces, stacked-ring chart.
        VaultPalette(
            id = "terra",
            name = "Terra Clay",
            tagline = "Terracotta and sage clay forms with thick soft shadows and comfort panels",
            inspiredBy = "Kiln pottery",
            isLight = true,
            iconStyle = IconStyle.ROUNDED,
            design = ThemeDesign.CLAYMORPHISM,
            backdrop = VaultBackdrop.SOFT_CLAY,
            layout = VaultLayout.GENERATIVE,
            abyssDark = Color(0xFFF3EADF),
            abyssSurface = Color(0xFFF8F1E7),
            abyssElevated = Color(0xFFEADCCB),
            abyssGlass = Color(0xE6FFF6EC),
            cyan = Color(0xFFA85433),
            amber = Color(0xFFD98A4E),
            indigo = Color(0xFF3E6B55),
            mint = Color(0xFF7A9E5E),
            coral = Color(0xFFC65B4C),
            lavender = Color(0xFFC08A8A),
            textPrimary = Color(0xFF33251A),
            textSecondary = Color(0xFF6B5847),
            textMuted = Color(0xFF8F7A68),
            glassBorder = Color(0x33A85433),
            glassHighlight = Color(0x4DFFFFFF),
            dangerDim = Color(0xFFF5DCD2),
            cardCorner = 34.dp,
            buttonCorner = 30.dp,
            inputCorner = 24.dp,
            chipCorner = 20.dp,
            navCorner = 34.dp,
            cardBorderWidth = 0.dp,
            cardFillAlpha = 0.96f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 20.dp,
            glow = false,
            meshIntensity = 0.4f,
            gradientButtons = false,
            embossed = true,
            navFloating = true,
            pressDepth = 2.dp,
            backdropBlur = 0.dp,
            sheen = false,
            dock = DockStyle.COMFORT_PANEL,
            header = HeaderStyle.LARGE_COMFORT,
            navIndicator = NavIndicator.PILL,
            navFrostAlpha = 0.97f,
            navHeight = 74.dp,
            navIconSize = 24.dp,
            navLabelSize = 11.sp,
            pieForm = PieForm.STACKED_RING,
            pieGapDegrees = 2f,
            pieRoundCaps = true,
            pieGradient = false,
            pieTrackAlpha = 0.3f,
            pieInnerRatio = 0.45f,
            pieLegend = PieLegend.DOT,
            pieSelectedLift = false
        ),
        // 12 — Royal Violet: deep violet/indigo premium dark with gold hairlines,
        // frosted capsule dock and gradient ring chart. Pairs with the crypto
        // panel's "Crypto Violet" mode.
        VaultPalette(
            id = "royal",
            name = "Royal Violet",
            tagline = "Deep violet premium dark with gold hairlines and a frosted capsule dock",
            inspiredBy = "Palace velvet",
            iconStyle = IconStyle.OUTLINED,
            design = ThemeDesign.BENTO,
            backdrop = VaultBackdrop.AURORA_MESH,
            layout = VaultLayout.STRIP,
            abyssDark = Color(0xFF120C24),
            abyssSurface = Color(0xFF1B1433),
            abyssElevated = Color(0xFF281E4A),
            abyssGlass = Color(0x99241B44),
            cyan = Color(0xFFA78BFA),
            amber = Color(0xFFD9B878),
            indigo = Color(0xFF6C5CE7),
            mint = Color(0xFF3ED6A4),
            coral = Color(0xFFF0557A),
            lavender = Color(0xFFC4B5FD),
            textPrimary = Color(0xFFF1ECFF),
            textSecondary = Color(0xFFBCB0DC),
            textMuted = Color(0xFF8478A8),
            glassBorder = Color(0x4DD9B878),
            glassHighlight = Color(0x24D9B878),
            dangerDim = Color(0xFF34182A),
            cardCorner = 24.dp,
            buttonCorner = 18.dp,
            inputCorner = 16.dp,
            chipCorner = 12.dp,
            navCorner = 30.dp,
            cardBorderWidth = 1.dp,
            cardFillAlpha = 0.8f,
            cardGradient = true,
            cardHighlight = true,
            cardShadow = 14.dp,
            glow = false,
            meshIntensity = 0.6f,
            gradientButtons = true,
            navFloating = true,
            backdropBlur = 20.dp,
            sheen = false,
            dock = DockStyle.PILL_FROSTED,
            header = HeaderStyle.CENTERED,
            navIndicator = NavIndicator.PILL,
            navFrostAlpha = 0.66f,
            navHeight = 66.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.RING,
            pieGapDegrees = 4f,
            pieRoundCaps = true,
            pieGradient = true,
            pieTrackAlpha = 0.32f,
            pieInnerRatio = 0.68f,
            pieLegend = PieLegend.BAR,
            pieSelectedLift = true
        ),
        // 13 — Slate Grid: neo-brutalist paper-white grid, hard 2.dp outlines,
        // near-square corners, no shadow, flat fused plate dock.
        VaultPalette(
            id = "slate",
            name = "Slate Grid",
            tagline = "Neo-brutalist paper grid with hard 2dp outlines and square-cut plates",
            inspiredBy = "Risograph poster",
            isLight = true,
            iconStyle = IconStyle.SHARP,
            // Same reason as Solar Pop: the brutalist grammar lives in the outlines and the
            // square corners; a solid icon plate would read as a dark block on white paper.
            design = ThemeDesign.NEUTRAL,
            backdrop = VaultBackdrop.FLAT_GRID,
            layout = VaultLayout.COLUMN,
            abyssDark = Color(0xFFF4F4EF),
            abyssSurface = Color(0xFFF4F4EF),
            abyssElevated = Color(0xFFE7E7DF),
            abyssGlass = Color(0xE6FFFFFF),
            cyan = Color(0xFFFFD400),
            amber = Color(0xFFFF7A00),
            indigo = Color(0xFF0066FF),
            mint = Color(0xFF00B84C),
            coral = Color(0xFFFF2D2D),
            lavender = Color(0xFF9A00FF),
            textPrimary = Color(0xFF121216),
            textSecondary = Color(0xFF3C3C46),
            textMuted = Color(0xFF62626C),
            glassBorder = Color(0xE617171B),
            glassHighlight = Color(0x0A000000),
            dangerDim = Color(0xFFFFDCD5),
            cardCorner = 6.dp,
            buttonCorner = 6.dp,
            inputCorner = 4.dp,
            chipCorner = 4.dp,
            navCorner = 0.dp,
            cardBorderWidth = 2.dp,
            cardFillAlpha = 1f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 0.dp,
            glow = false,
            meshIntensity = 0.2f,
            gradientButtons = false,
            hardOutline = true,
            navFloating = false,
            uppercaseLabels = true,
            monoTags = true,
            pressDepth = 3.dp,
            backdropBlur = 0.dp,
            sheen = false,
            dock = DockStyle.FLAT_PLATE,
            header = HeaderStyle.INLINE_BOLD,
            navIndicator = NavIndicator.UNDERLINE,
            navFrostAlpha = 1f,
            navHeight = 70.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.RING,
            pieGapDegrees = 0f,
            pieRoundCaps = false,
            pieGradient = false,
            pieTrackAlpha = 0.3f,
            pieInnerRatio = 0.55f,
            pieLegend = PieLegend.SQUARE,
            pieSelectedLift = false
        ),
        // 14 — Sakura Tint: soft pink/plum clay pastels, rounded everything,
        // floating dock with dot indicators.
        VaultPalette(
            id = "sakura",
            name = "Sakura Tint",
            tagline = "Pink and plum clay pastels, rounded everything, floating dock with dots",
            inspiredBy = "Cherry blossom",
            isLight = true,
            iconStyle = IconStyle.ROUNDED,
            design = ThemeDesign.CLAYMORPHISM,
            backdrop = VaultBackdrop.PULSE_MESH,
            layout = VaultLayout.BENTO,
            abyssDark = Color(0xFFFBEFF3),
            abyssSurface = Color(0xFFFEF7FA),
            abyssElevated = Color(0xFFF4E2EA),
            abyssGlass = Color(0xE6FFFFFF),
            cyan = Color(0xFFB84A78),
            amber = Color(0xFFE5A75F),
            indigo = Color(0xFF6C5CE0),
            mint = Color(0xFF58A574),
            coral = Color(0xFFD95555),
            lavender = Color(0xFFB18AE0),
            textPrimary = Color(0xFF33222A),
            textSecondary = Color(0xFF6E5462),
            textMuted = Color(0xFF957C8C),
            glassBorder = Color(0x33B84A78),
            glassHighlight = Color(0x4DFFFFFF),
            dangerDim = Color(0xFFFADDE1),
            cardCorner = 30.dp,
            buttonCorner = 26.dp,
            inputCorner = 22.dp,
            chipCorner = 22.dp,
            navCorner = 32.dp,
            cardBorderWidth = 0.dp,
            cardFillAlpha = 0.9f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 16.dp,
            glow = false,
            meshIntensity = 0.45f,
            gradientButtons = true,
            embossed = true,
            navFloating = true,
            pressDepth = 2.dp,
            backdropBlur = 12.dp,
            sheen = false,
            dock = DockStyle.SOFT_FLOATING,
            header = HeaderStyle.LARGE_COMFORT,
            navIndicator = NavIndicator.DOT,
            navFrostAlpha = 0.88f,
            navHeight = 72.dp,
            navIconSize = 22.dp,
            navLabelSize = 10.sp,
            pieForm = PieForm.STACKED_RING,
            pieGapDegrees = 3f,
            pieRoundCaps = true,
            pieGradient = true,
            pieTrackAlpha = 0.25f,
            pieInnerRatio = 0.5f,
            pieLegend = PieLegend.DOT,
            pieSelectedLift = false
        ),
        // 15 — Carbon Pro: high-contrast graphite dark, signal orange, textured
        // backdrop, bold monospace numerics on a comfort panel dock.
        VaultPalette(
            id = "carbon",
            name = "Carbon Pro",
            tagline = "High-contrast graphite with signal orange, texture and mono numerics",
            inspiredBy = "Instrument panel",
            iconStyle = IconStyle.FILLED,
            design = ThemeDesign.BENTO,
            backdrop = VaultBackdrop.TEXTURED,
            layout = VaultLayout.AGENTIC,
            abyssDark = Color(0xFF101215),
            abyssSurface = Color(0xFF1D2025),
            abyssElevated = Color(0xFF2A2E35),
            abyssGlass = Color(0xCC22262C),
            cyan = Color(0xFFFF8A00),
            amber = Color(0xFFFFD000),
            indigo = Color(0xFF3D9BFF),
            mint = Color(0xFF00E676),
            coral = Color(0xFFFF3B47),
            lavender = Color(0xFFB388FF),
            textPrimary = Color(0xFFFAFBFD),
            textSecondary = Color(0xFFCBD2DC),
            textMuted = Color(0xFF9AA3AF),
            glassBorder = Color(0x2E8B95A3),
            glassHighlight = Color(0x14FFFFFF),
            dangerDim = Color(0xFF3A2318),
            cardCorner = 14.dp,
            buttonCorner = 10.dp,
            inputCorner = 10.dp,
            chipCorner = 6.dp,
            navCorner = 18.dp,
            cardBorderWidth = 1.5.dp,
            cardFillAlpha = 0.97f,
            cardGradient = false,
            cardHighlight = false,
            cardShadow = 0.dp,
            glow = false,
            meshIntensity = 0.35f,
            gradientButtons = false,
            navFloating = false,
            monoTags = true,
            backdropBlur = 0.dp,
            sheen = false,
            dock = DockStyle.COMFORT_PANEL,
            header = HeaderStyle.TAB_UPPER,
            navIndicator = NavIndicator.PILL,
            navFrostAlpha = 0.98f,
            navHeight = 76.dp,
            navIconSize = 24.dp,
            navLabelSize = 11.sp,
            pieForm = PieForm.WEDGE,
            pieGapDegrees = 1f,
            pieRoundCaps = false,
            pieGradient = false,
            pieTrackAlpha = 0.35f,
            pieInnerRatio = 0.9f,
            pieLegend = PieLegend.BAR,
            pieSelectedLift = false
        )
    )

    /**
     * Retired theme ids mapped onto the closest surviving theme. A theme id lives in
     * DataStore across upgrades, so anything still pointing at a deleted palette would
     * silently fall through to the default; every id this app ever shipped has to land
     * here.
     */
    private val legacyAliases = mapOf(
        // Retired squircle and brand ids land on Solar Pop, the closest vivid light board.
        "ios" to "solstice",
        "ios_glass" to "solstice",
        "apple" to "solstice",
        "iphone" to "solstice",
        "frosted" to "solstice",
        "generative" to "solstice",
        "hyper" to "solstice",
        "hyper_shell" to "solstice",
        "hyperos" to "solstice",
        "miui" to "solstice",
        "xiaomi" to "solstice",
        "clay" to "solstice",
        "claymorphism_3d" to "solstice",
        "funtouch" to "solstice",
        "vivo" to "solstice",
        // Samsung.
        "one_ui" to "oneui",
        "samsung" to "oneui",
        "comfort" to "oneui",
        // ColorOS/Oppo family, plus the old glass-and-aurora ids.
        "liquid" to "aqua",
        "glassmorphism" to "aqua",
        "glass" to "aqua",
        "aurora" to "aqua",
        "coloros" to "aqua",
        "oppo" to "aqua",
        "realme" to "aqua",
        // Plain light surfaces and every retired flat-light id land on Neon Lime.
        "light" to "lime",
        "material_light" to "lime",
        "lumen" to "lime",
        "lumen_white" to "lime",
        "clean" to "lime",
        "clean_light" to "lime",
        "cleanlight" to "lime",
        "porcelain" to "lime",
        "fluent" to "lime",
        "agentic" to "lime",
        "material" to "lime",
        "material_you" to "lime",
        "light_mode" to "lime",
        // "slate" also names a shipped theme now; find() matches VaultPalettes.all
        // first, so this alias only ever fires for the pre-theme-list builds.
        "slate" to "dark",
        "slate_grid" to "dark",
        "bento" to "dark",
        "bento_grid" to "dark",
        "bentogrid" to "dark",
        "skeuomorphic" to "dark",
        "neo_skeuomorphism" to "dark",
        "neoskeuomorphism" to "dark",
        "abyss" to "dark",
        "obsidian" to "dark",
        "obsidian_dark" to "dark",
        "obsidiandark" to "dark",
        "ember" to "dark",
        "emerald" to "dark",
        "volt" to "dark",
        "volt_x" to "dark",
        "xos" to "dark",
        "infinix" to "dark",
        "brutalist" to "dark",
        "neo_brutalism" to "dark",
        "neobrutalism" to "dark",
        "cyberpunk" to "dark",
        "cyberpunk_glow" to "dark",
        "neon" to "dark",
        "neon_glow" to "dark",
        "huawei" to "dark",
        "emui" to "dark",
        "material_dark" to "dark",
        "oled" to "dark",
        "black" to "dark"
    )

    fun find(id: String?): VaultPalette {
        val normalized = id?.trim()?.lowercase()
        return all.firstOrNull { it.id == normalized }
            ?: legacyAliases[normalized]?.let { alias -> all.firstOrNull { it.id == alias } }
            ?: all.first { it.id == DEFAULT_ID }
    }
}

private val activePalette = mutableStateOf(VaultPalettes.find(VaultPalettes.DEFAULT_ID))

object VaultPaletteController {
    val active: State<VaultPalette> get() = activePalette
    val current: VaultPalette get() = activePalette.value

    fun apply(id: String?) {
        val next = VaultPalettes.find(id)
        if (next.id != activePalette.value.id) activePalette.value = next
    }

    fun apply(palette: VaultPalette) {
        if (palette.id != activePalette.value.id) activePalette.value = palette
    }
}
