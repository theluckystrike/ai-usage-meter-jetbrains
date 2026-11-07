package zovo.aiusage.ide;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/** Java, like the widget, so no bridges to deprecated default methods are generated. */
public final class UsageWidgetFactory implements StatusBarWidgetFactory {
    @Override public @NotNull String getId() { return UsageWidget.ID; }
    @Override public @NotNull String getDisplayName() { return "AI Usage Meter"; }
    @Override public boolean isAvailable(@NotNull Project project) { return true; }
    @Override public @NotNull StatusBarWidget createWidget(@NotNull Project project) { return new UsageWidget(project); }
    @Override public boolean canBeEnabledOn(@NotNull StatusBar statusBar) { return true; }
}
