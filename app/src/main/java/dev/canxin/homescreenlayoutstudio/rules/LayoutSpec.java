package dev.canxin.homescreenlayoutstudio.rules;

public final class LayoutSpec {
    public int spanX = 1;
    public int spanY = 1;
    public int priority = 0;
    public Integer preferredScreen;
    public Integer manualScreen;
    public Integer manualCellX;
    public Integer manualCellY;

    public LayoutSpec copy() {
        LayoutSpec copy = new LayoutSpec();
        copy.spanX = spanX;
        copy.spanY = spanY;
        copy.priority = priority;
        copy.preferredScreen = preferredScreen;
        copy.manualScreen = manualScreen;
        copy.manualCellX = manualCellX;
        copy.manualCellY = manualCellY;
        return copy;
    }

    public boolean hasManualPosition() {
        return manualScreen != null && manualCellX != null && manualCellY != null;
    }
}
