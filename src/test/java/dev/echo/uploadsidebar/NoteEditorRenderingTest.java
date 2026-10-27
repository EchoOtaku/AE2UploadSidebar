package dev.echo.uploadsidebar;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class NoteEditorRenderingTest {
    @Test void screenBackgroundAndWidgetsRenderOnceBeforeCustomLabels() throws Exception {
        var calls = new ArrayList<String>();
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "dev/echo/uploadsidebar/client/NoteEditorScreen.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("render")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            if (owner.equals("net/minecraft/client/gui/screens/Screen") && method.equals("render")) calls.add("base");
                            if (method.equals("renderBackground")) calls.add("background");
                            if (method.equals("drawCenteredString") || method.equals("drawClipped") || method.equals("drawString")) calls.add("label");
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        // MC 1.21.1 Screen.render itself paints/blur-renders the background, then widgets.
        assertEquals("base", calls.getFirst(), "custom labels must not precede the base screen's background");
        assertEquals(1, calls.stream().filter("base"::equals).count());
        assertFalse(calls.contains("background"), "do not render the background twice");
        assertTrue(calls.contains("label"));
    }
}
