package nl.requios.effortlessbuilding.shape;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape the player builds with in SHAPE mode, chosen in the Shape Generator screen,
 * plus the player's saved templates. Client-side only; the server receives the active
 * shape inside each build packet.
 */
public final class ShapeClientState {

    /** A named, saved shape design. */
    public record Template(String name, ShapeParams params) {}

    private static ShapeParams active = ShapeParams.defaults(ShapeType.GEAR);
    private static final List<Template> templates = new ArrayList<>();
    /** Index of the template the active shape came from, or -1. */
    private static int templateIndex = -1;

    private ShapeClientState() {}

    public static ShapeParams getActive() {
        return active;
    }

    /** Sets the active shape directly (not from a template). */
    public static void setActive(ShapeParams params) {
        active = params;
        templateIndex = -1;
    }

    /** Changes the active shape (e.g. its sizing) without forgetting which template it came from. */
    public static void setActiveKeepTemplate(ShapeParams params) {
        active = params;
    }

    public static List<Template> getTemplates() {
        return List.copyOf(templates);
    }

    /** Replaces the template list, e.g. after loading from disk. */
    public static void setTemplates(List<Template> list) {
        templates.clear();
        templates.addAll(list);
        templateIndex = -1;
    }

    /** Adds or overwrites (by name) a template. */
    public static void saveTemplate(Template template) {
        templates.removeIf(t -> t.name().equalsIgnoreCase(template.name()));
        templates.add(template);
    }

    public static void deleteTemplate(String name) {
        templates.removeIf(t -> t.name().equalsIgnoreCase(name));
        templateIndex = -1;
    }

    /** Makes the template the active shape. */
    public static void useTemplate(Template template) {
        active = template.params();
        templateIndex = templates.indexOf(template);
    }

    /**
     * Steps to the next ({@code +1}) or previous ({@code -1}) template and makes it active.
     *
     * @return the now active template, or null if there are none
     */
    public static @Nullable Template cycleTemplate(int direction) {
        if (templates.isEmpty()) return null;
        int n = templates.size();
        templateIndex = templateIndex < 0
                ? (direction > 0 ? 0 : n - 1)
                : Math.floorMod(templateIndex + direction, n);
        active = templates.get(templateIndex).params();
        return templates.get(templateIndex);
    }
}
