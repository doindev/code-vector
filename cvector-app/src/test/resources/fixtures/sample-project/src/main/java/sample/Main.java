package sample;

public class Main {

    public static void main(String[] args) {
        Greeter g = new Greeter("hello");
        System.out.println(g.greet("world"));
        System.out.println(g.shout("there"));
    }
}
