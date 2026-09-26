import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;

/**
 * Focus the n-th focusable widget of the open screen with real Tab presses, clear it, and paste a
 * value. Real key events are the only ones a container screen's EditBox sees: the bridge reflects
 * a (char,int) method the screen does not declare, and Minecraft reads the live GLFW modifier
 * state for Ctrl+V.
 *
 * usage: Field <tabs> [text]   -- with no text the field is only focused and cleared.
 */
public class Field {
    public static void main(String[] args) throws Exception {
        int tabs = Integer.parseInt(args[0]);
        Robot robot = new Robot();
        robot.setAutoDelay(40);
        for (int i = 0; i < tabs; i++) {
            robot.keyPress(KeyEvent.VK_TAB);
            robot.keyRelease(KeyEvent.VK_TAB);
        }
        Thread.sleep(150);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_A);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_BACK_SPACE);
        robot.keyRelease(KeyEvent.VK_BACK_SPACE);
        if (args.length > 1 && !args[1].isEmpty()) {
            StringSelection selection = new StringSelection(args[1]);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            Thread.sleep(250);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
        }
        Thread.sleep(200);
    }
}
