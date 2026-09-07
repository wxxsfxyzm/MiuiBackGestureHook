    .text

    // These hooks replace exact four-instruction Dart return epilogues after
    // Xiaomi has published its state. They never call a Dart trampoline or a
    // C/C++ function. The observer preserves all live registers and NZCV,
    // performs only atomic memory operations plus a raw eventfd write, then
    // emulates the displaced epilogue and returns to the original caller.
    .equ DART_EPILOGUE_SCRATCH_SIZE, 0x60

    .macro save_epilogue_scratch
    sub sp, sp, #DART_EPILOGUE_SCRATCH_SIZE
    stp x0, x1, [sp, #0x00]
    stp x2, x8, [sp, #0x10]
    stp x12, x13, [sp, #0x20]
    stp x14, x15, [sp, #0x30]
    stp x16, x17, [sp, #0x40]
    mrs x17, nzcv
    str x17, [sp, #0x50]
    .endm

    .macro restore_epilogue_scratch
    ldr x17, [sp, #0x50]
    msr nzcv, x17
    ldp x0, x1, [sp, #0x00]
    ldp x2, x8, [sp, #0x10]
    ldp x12, x13, [sp, #0x20]
    ldp x14, x15, [sp, #0x30]
    ldp x16, x17, [sp, #0x40]
    add sp, sp, #DART_EPILOGUE_SCRATCH_SIZE
    .endm

    .macro mark_dart_state_pending mask
    adrp x16, g_dart_state_publish_pending
    add x16, x16, :lo12:g_dart_state_publish_pending
.Lpending_retry\@:
    ldxr w17, [x16]
    orr w17, w17, #\mask
    stlxr w12, w17, [x16]
    cbnz w12, .Lpending_retry\@
    .endm

    .macro wake_dart_state_publisher
    mov x17, #1
    str x17, [sp, #0x58]
    adrp x16, g_dart_state_publish_event_fd
    add x16, x16, :lo12:g_dart_state_publish_event_fd
    ldar w0, [x16]
    tbnz w0, #31, .Lwake_done\@
    add x1, sp, #0x58
    mov x2, #8
    mov x8, #64
    svc #0
.Lwake_done\@:
    .endm

    .macro store_dart_observation symbol, value, unchanged
    adrp x16, g_dart_state_owner_epoch
    add x16, x16, :lo12:g_dart_state_owner_epoch
    ldar x13, [x16]
    lsl x17, x13, #2
    orr x17, x17, #\value
    adrp x16, \symbol
    add x16, x16, :lo12:\symbol
.Lobservation_retry\@:
    ldaxr x12, [x16]
    cmp x12, x17
    b.eq .Lobservation_unchanged\@
    lsr x14, x12, #2
    cmp x14, x13
    b.hi .Lobservation_unchanged\@
    stlxr w14, x17, [x16]
    cbnz w14, .Lobservation_retry\@
    b .Lobservation_stored\@
.Lobservation_unchanged\@:
    clrex
    b \unchanged
.Lobservation_stored\@:
    .endm

    .macro increment_dart_counter symbol
    adrp x16, \symbol
    add x16, x16, :lo12:\symbol
.Lcounter_retry\@:
    ldxr w17, [x16]
    add w17, w17, #1
    stxr w12, w17, [x16]
    cbnz w12, .Lcounter_retry\@
    .endm

    .macro enter_dart_observer counter, retiring, leave
    adrp x16, \retiring
    add x16, x16, :lo12:\retiring
    adrp x13, \counter
    add x13, x13, :lo12:\counter
.Lactive_enter_retry\@:
    ldaxr w17, [x13]
    add w17, w17, #1
    stlxr w12, w17, [x13]
    cbnz w12, .Lactive_enter_retry\@
    ldar w17, [x16]
    cbnz w17, \leave
    .endm

    .macro leave_dart_observer counter
    adrp x16, \counter
    add x16, x16, :lo12:\counter
.Lactive_leave_retry\@:
    ldaxr w17, [x16]
    cbz w17, .Lactive_leave_zero\@
    sub w17, w17, #1
    stlxr w12, w17, [x16]
    cbnz w12, .Lactive_leave_retry\@
    b .Lactive_leave_done\@
.Lactive_leave_zero\@:
    clrex
.Lactive_leave_done\@:
    .endm

    .macro finish_return_x22
    restore_epilogue_scratch
    mov x0, x22
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
    .endm

    .p2align 2
    .global MiuiHomeHyosDartDrawerTransitionEpilogueHook
    .hidden MiuiHomeHyosDartDrawerTransitionEpilogueHook
    .type MiuiHomeHyosDartDrawerTransitionEpilogueHook, %function
MiuiHomeHyosDartDrawerTransitionEpilogueHook:
    bti c
    save_epilogue_scratch
    enter_dart_observer g_dart_drawer_active_count, g_dart_drawer_retiring, .Ldrawer_leave
    // The original Dart frame still owns the visibility argument at x29+16.
    ldr x12, [x29, #0x10]
    add x13, x22, #0x20
    cmp x12, x13
    b.eq .Ldrawer_visible
    add x13, x22, #0x30
    cmp x12, x13
    b.ne .Ldrawer_leave
    store_dart_observation g_drawer_state_observation, 1, .Ldrawer_leave
    mark_dart_state_pending 1
    b .Ldrawer_wake
.Ldrawer_visible:
    store_dart_observation g_drawer_state_observation, 2, .Ldrawer_leave
    mark_dart_state_pending 1
.Ldrawer_wake:
    wake_dart_state_publisher
.Ldrawer_leave:
    leave_dart_observer g_dart_drawer_active_count
    restore_epilogue_scratch
    mov x0, x22
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
    .size MiuiHomeHyosDartDrawerTransitionEpilogueHook, .-MiuiHomeHyosDartDrawerTransitionEpilogueHook

    .p2align 2
    .global MiuiHomeHyosDartOverviewEnterEpilogueHook
    .hidden MiuiHomeHyosDartOverviewEnterEpilogueHook
    .type MiuiHomeHyosDartOverviewEnterEpilogueHook, %function
MiuiHomeHyosDartOverviewEnterEpilogueHook:
    bti c
    save_epilogue_scratch
    enter_dart_observer g_dart_overview_enter_active_count, g_dart_overview_enter_retiring, .Loverview_enter_leave
    increment_dart_counter g_overview_dart_enter_count
    store_dart_observation g_overview_state_observation, 2, .Loverview_enter_leave
    mark_dart_state_pending 2
    wake_dart_state_publisher
.Loverview_enter_leave:
    leave_dart_observer g_dart_overview_enter_active_count
    finish_return_x22
    .size MiuiHomeHyosDartOverviewEnterEpilogueHook, .-MiuiHomeHyosDartOverviewEnterEpilogueHook

    .p2align 2
    .global MiuiHomeHyosDartOverviewExitEpilogueHook
    .hidden MiuiHomeHyosDartOverviewExitEpilogueHook
    .type MiuiHomeHyosDartOverviewExitEpilogueHook, %function
MiuiHomeHyosDartOverviewExitEpilogueHook:
    bti c
    save_epilogue_scratch
    enter_dart_observer g_dart_overview_exit_active_count, g_dart_overview_exit_retiring, .Loverview_exit_leave
    increment_dart_counter g_overview_dart_exit_count
    store_dart_observation g_overview_state_observation, 1, .Loverview_exit_leave
    mark_dart_state_pending 2
    wake_dart_state_publisher
.Loverview_exit_leave:
    leave_dart_observer g_dart_overview_exit_active_count
    finish_return_x22
    .size MiuiHomeHyosDartOverviewExitEpilogueHook, .-MiuiHomeHyosDartOverviewExitEpilogueHook

    .p2align 2
    .global MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook
    .hidden MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook
    .type MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook, %function
MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook:
    bti c
    save_epilogue_scratch
    enter_dart_observer g_dart_editing_active_count, g_dart_editing_retiring, .Lhome_surface_leave
    b .Lhome_surface_inactive
    .size MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook, .-MiuiHomeHyosDartHomeSurfaceInactiveEpilogueHook

    .p2align 2
    .global MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook
    .hidden MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook
    .type MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook, %function
MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook:
    bti c
    save_epilogue_scratch
    enter_dart_observer g_dart_editing_active_count, g_dart_editing_retiring, .Lhome_surface_leave
    // The resolver proves this notifyBackGestureStatus frame and its context
    // stores/loads through the original setGestureRegionState call. Observe the
    // final decision, including caller-generated false and source adjustment.
    ldur x12, [x29, #-16]
    ldur x13, [x29, #-8]
    ldur w14, [x12, #15]
    cmp w14, w13
    b.ne .Lhome_surface_inactive
    ldur w13, [x12, #27]
    add w14, w22, #0x20
    cmp w13, w14
    b.ne .Lhome_surface_inactive
    ldur w12, [x12, #31]
    add x12, x12, x28, lsl #32
    tbz w12, #0, .Lhome_surface_inactive
    cmp w12, w22
    b.eq .Lhome_surface_inactive
    // String.___0 in this same image proves CID, length and character layout.
    ldur x13, [x12, #-1]
    ubfx x13, x13, #12, #20
    cmp x13, #94
    b.ne .Lhome_surface_inactive
    ldur x13, [x12, #7]
    cmp x13, #(17 * 2)
    b.ne .Lhome_surface_inactive
    add x12, x12, #15
    adrp x13, .Lhome_surface_source
    add x13, x13, :lo12:.Lhome_surface_source
    ldr x14, [x12]
    ldr x17, [x13]
    cmp x14, x17
    b.ne .Lhome_surface_inactive
    ldr x14, [x12, #8]
    ldr x17, [x13, #8]
    cmp x14, x17
    b.ne .Lhome_surface_inactive
    ldrb w14, [x12, #16]
    ldrb w17, [x13, #16]
    cmp w14, w17
    b.ne .Lhome_surface_inactive
    store_dart_observation g_editing_state_observation, 2, .Lhome_surface_leave
    b .Lhome_surface_publish
.Lhome_surface_inactive:
    store_dart_observation g_editing_state_observation, 1, .Lhome_surface_leave
.Lhome_surface_publish:
    increment_dart_counter g_editing_dart_observe_count
    mark_dart_state_pending 4
    wake_dart_state_publisher
.Lhome_surface_leave:
    leave_dart_observer g_dart_editing_active_count
    finish_return_x22
    .size MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook, .-MiuiHomeHyosDartHomeSurfacePublishedEpilogueHook

    .section .rodata
    .p2align 3
.Lhome_surface_source:
    .ascii "typefrom_home_sub"

    .section .note.gnu.property, "a"
    .p2align 3
    .long 4
    .long 16
    .long 5
    .asciz "GNU"
    .p2align 3
    .long 0xc0000000
    .long 4
    .long 1
    .long 0

    .section .note.GNU-stack,"",%progbits
