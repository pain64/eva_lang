void some() {
}
char bar() {
     __asm (
	"mov %al, %al\n"
	"mov %ah, %ah\n"
	"mov %bx, %ax\n"
	"mov %eax, %eax\n"
	"mov %rax, %rax\n"

	"mov $0, %al\n"
	"mov $(.local2 - .local), %al\n"
	"mov $300, %ax\n"
	"mov $1000000, %eax\n"
	"mov $(.fail - .local), %ax\n"
	"callq .some\n"
	".local:\n"
	"je .local\n"
	"movq $0, %rax\n"
	".local2:\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	"movq $0, %rax\n"
	".fail:\n"
    );
    some();
    return 42;
}
short main() {
    short x = 0;
    return 0;
}