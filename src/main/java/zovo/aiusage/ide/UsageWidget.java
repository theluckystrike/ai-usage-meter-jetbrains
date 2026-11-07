package zovo.aiusage.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * Status bar text such as "Claude 5h 34% wk 61% · Codex 5h 12%". The tooltip has today's tokens and cost per tool.
 * A click opens the AI Usage tool window. Written in Java so no bridges to deprecated default methods are made.
 */
public final class UsageWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {
    public static final String ID = "zovo.aiusage.widget";
    public static final String TOOL_WINDOW = "AI Usage";

    private final Project project;
    private volatile StatusBar statusBar;

    public UsageWidget(@NotNull Project project) { this.project = project; }

    @Override public @NotNull String ID() { return ID; }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        UsageService service = UsageService.Companion.get();
        service.addListener(this, () -> {
            StatusBar bar = this.statusBar;
            if (bar != null) bar.updateWidget(ID);
            return kotlin.Unit.INSTANCE;
        });
        service.ensureStarted();
    }

    @Override public @Nullable WidgetPresentation getPresentation() { return this; }

    @Override
    public @NotNull String getText() {
        UsageService.Snapshot s = UsageService.Companion.get().getSnapshot();
        return s == null ? "AI usage: reading…" : s.getView().getText();
    }

    @Override public float getAlignment() { return Component.CENTER_ALIGNMENT; }

    @Override
    public @Nullable String getTooltipText() {
        UsageService.Snapshot s = UsageService.Companion.get().getSnapshot();
        return s == null ? "AI Usage Meter is reading your local usage files." : s.getView().tooltipHtml();
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            if (project.isDisposed()) return;
            ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW);
            if (tw != null) tw.show();
        };
    }

    @Override public void dispose() { statusBar = null; }
}
