import java.lang.reflect.*;

public class InspectPostChain {
    public static void main(String[] args) {
        try {
            printClass(Class.forName("net.minecraft.client.renderer.PostChainConfig"));
            printClass(Class.forName("net.minecraft.client.renderer.PostChainConfig$Pass"));
            printClass(Class.forName("net.minecraft.client.renderer.PostChainConfig$Input"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printClass(Class<?> cls) {
        System.out.println("========================================");
        System.out.println("Class: " + cls.getName());
        System.out.println("========================================");
        System.out.println("Constructors:");
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            System.out.println("  " + c);
        }
        System.out.println("Fields:");
        for (Field f : cls.getDeclaredFields()) {
            System.out.println("  " + f);
        }
        System.out.println("Methods:");
        for (Method m : cls.getDeclaredMethods()) {
            System.out.println("  " + m);
        }
    }
}
