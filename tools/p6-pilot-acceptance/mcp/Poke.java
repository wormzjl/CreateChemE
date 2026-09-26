import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Desktop input for the dev client, for the one thing the bridge cannot do for itself: reaching
 * ESC &gt; MCP Take Over so that control mode exists at all. Coordinates are absolute screen pixels.
 *
 * usage: Poke click X Y | Poke key NAME | Poke type TEXT
 */
public class Poke {
    public static void main(String[] args) throws Exception {
        Robot robot = new Robot();
        robot.setAutoDelay(60);
        switch (args[0]) {
            case "click" -> {
                robot.mouseMove(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                robot.delay(200);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.delay(80);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }
            case "move" -> robot.mouseMove(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            case "paste" -> {
                // Clipboard, not synthesized characters: a host IME eats letter keystrokes, and
                // Minecraft reads the live GLFW modifier state for Ctrl+V.
                var selection = new java.awt.datatransfer.StringSelection(args[1]);
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
                robot.delay(250);
                robot.keyPress(KeyEvent.VK_CONTROL);
                robot.keyPress(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_CONTROL);
            }
            case "clear" -> {
                robot.keyPress(KeyEvent.VK_CONTROL);
                robot.keyPress(KeyEvent.VK_A);
                robot.keyRelease(KeyEvent.VK_A);
                robot.keyRelease(KeyEvent.VK_CONTROL);
                robot.delay(80);
                robot.keyPress(KeyEvent.VK_BACK_SPACE);
                robot.keyRelease(KeyEvent.VK_BACK_SPACE);
            }
            case "key" -> {
                int code = KeyEvent.class.getField("VK_" + args[1].toUpperCase()).getInt(null);
                robot.keyPress(code);
                robot.delay(60);
                robot.keyRelease(code);
            }
            case "type" -> {
                for (char c : args[1].toCharArray()) {
                    boolean shift = Character.isUpperCase(c) || ":_!\"".indexOf(c) >= 0;
                    int code = switch (c) {
                        case ' ' -> KeyEvent.VK_SPACE;
                        case ':' -> KeyEvent.VK_SEMICOLON;
                        case '_' -> KeyEvent.VK_MINUS;
                        case '-' -> KeyEvent.VK_MINUS;
                        case '.' -> KeyEvent.VK_PERIOD;
                        case '/' -> KeyEvent.VK_SLASH;
                        default -> KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(c));
                    };
                    if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
                    robot.keyPress(code);
                    robot.delay(30);
                    robot.keyRelease(code);
                    if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
                }
            }
            default -> throw new IllegalArgumentException("unknown action " + args[0]);
        }
        robot.delay(200);
        System.out.println("ok " + String.join(" ", args));
    }
}
