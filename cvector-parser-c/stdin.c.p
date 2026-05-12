#include <stdio.h>

struct Point {
    int x;
    int y;
};

enum Color { RED, GREEN, BLUE };

int add(int a, int b) {
    return a + b;
}

int main(int argc, char** argv) {
    return add(1, 2);
}
