import java.awt.Robot;
import java.awt.event.KeyEvent;

/** Taps F3 once with a real key event (the bridge's key injection does not toggle the debug overlay). */
public class KeyTap {
    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(60);
        robot.keyPress(KeyEvent.VK_F3);
        robot.keyRelease(KeyEvent.VK_F3);
        System.out.println("tapped F3");
    }
}
