@ 512-byte SD chainloader stage for the Fujifilm X half.
@
@ Entered by the shell command `sdboot 0x41070000`, which reads the first
@ sector of the SD reserved slot 0 to 0x41070000 and jumps.  The full
@ main image has already been staged in high DRAM over the USB vendor
@ receiver.  This stage copies the staged image to 0x40000000 with cache
@ maintenance and jumps to the image reset vector.
@
@ It runs from the free DRAM gap (not the destination range), so the
@ copy cannot overwrite the running code until the destination itself is
@ written.
@
@ Build-time constants (patched by stage_build.py):
@   SRC  = 0x50000000
@   DST  = 0x40000000
@   LEN  = 0x00e38400

        .syntax unified
        .arm
        .text
        .global _start

_start:
        cpsid   i

        @ Invalidate D-cache lines over the staged source range so the
        @ copy reads DRAM, not stale cache lines.
        ldr     r0, =0x50000000
        ldr     r2, =0x00e38400
        add     r2, r2, r0
1:      mcr     p15, 0, r0, c7, c6, 1      @ DCIMVAC
        add     r0, r0, #64
        cmp     r0, r2
        blo     1b

        @ Copy source -> destination, cleaning/invalidating each
        @ destination cache line and invalidating the I-cache line.
        ldr     r0, =0x50000000
        ldr     r1, =0x40000000
        ldr     r2, =0x00e38400
2:      ldmia   r0!, {r3-r10}
        stmia   r1!, {r3-r10}
        sub     r12, r1, #32
        bic     r12, r12, #63
        mcr     p15, 0, r12, c7, c14, 1    @ DCCIMVAC
        mcr     p15, 0, r12, c7, c5, 1     @ ICIMVAU
        subs    r2, r2, #32
        bne     2b

        mov     r0, #0
        mcr     p15, 0, r0, c7, c10, 5     @ DSB
        mcr     p15, 0, r0, c7, c5, 4      @ ISB
        ldr     r0, =0x40000000
        bx      r0
3:      b       3b

        .ltorg
        .align  5
stage_end:
