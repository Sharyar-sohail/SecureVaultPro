# UI/UX & ANTIGRAVITY DESIGN EXPERT RULES

All future UI/UX development in this workspace must strictly follow:
1. **Design Guidelines & Spatial Vibe (`antigravity-design-expert`):**
   - **Weightlessness:** Elements appear to float with layered, soft, diffused drop shadows (e.g. `box-shadow: 0 20px 40px rgba(0,0,0,0.05)`).
   - **Spatial Depth:** Z-axis layering, depth perspective, and elevated foreground components.
   - **Glassmorphism:** Subtle translucency, high-performance backdrop blur (`backdrop-filter: blur(12px)`), and semi-transparent borders.
   - **Isometric & Motion Dynamics:** Smooth 60fps animations, staggered card entrance drop-ins (0.1s stagger), and native scrolling preservation.
2. **Strict Negatives (Forbidden AI Tropes):**
   - NO generic AI-generated purple/grey flat gradient fills.
   - NO repetitive boilerplate cards with identical rounded rectangles.
   - NO meaningless minimalist placeholder icons.
   - NO uninspired flat design where high-contrast spatial depth is required.
3. **Execution & Token Conservation:**
   - Keep code updates targeted, modular, and component-specific (diff-only or surgical edits).
   - Prioritize performance: Never animate expensive properties (`box-shadow` or `filter`) continuously. Respect `prefers-reduced-motion`.
