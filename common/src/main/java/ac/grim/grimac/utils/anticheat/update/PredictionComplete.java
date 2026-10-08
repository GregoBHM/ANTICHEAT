package ac.grim.grimac.utils.anticheat.update;

public class PredictionComplete {
    private double offset;
    private PositionUpdate data;
    private boolean checked;
    private int identifier;
    private boolean safePositionUpdateBlocked;

    public PredictionComplete(double offset, PositionUpdate update, boolean checked) {
        this.offset = offset;
        this.data = update;
        this.checked = checked;
    }

    public double getOffset() {
        return offset;
    }

    public void setOffset(double offset) {
        this.offset = offset;
    }

    public PositionUpdate getData() {
        return data;
    }

    public void setData(PositionUpdate data) {
        this.data = data;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public int getIdentifier() {
        return identifier;
    }

    public void setIdentifier(int identifier) {
        this.identifier = identifier;
    }

    public boolean isSafePositionUpdateBlocked() {
        return safePositionUpdateBlocked;
    }

    public void setSafePositionUpdateBlocked(boolean safePositionUpdateBlocked) {
        this.safePositionUpdateBlocked = safePositionUpdateBlocked;
    }
}
