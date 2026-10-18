package com.kira3.ametrine.emucore

/**
 * A Saturn Control Pad button. [code] is its bit in the core's gamepad input
 * (IODevice_Gamepad_IDII in Mednafen's src/ss/input/gamepad.cpp).
 */
enum class PadButton(internal val code: Int) {
    Up(4),
    Down(5),
    Left(6),
    Right(7),
    A(10),
    B(8),
    C(9),
    X(2),
    Y(1),
    Z(0),
    L(15),
    R(3),
    Start(11),

    /**
     * The 3D Control Pad's mode switch, as a button: a press (an edge — releases carry 0) flips
     * that pad between analog and digital. Past the gamepad's bits at code 16, and not a Saturn
     * button: with a standard pad connected, the core ignores it.
     */
    Analog(16),
}

/**
 * A stick of the 3D Control Pad. The core takes the [Left] one (the pad has a single stick),
 * when a 3D Control Pad is connected; [Right] moves nothing it reads.
 */
enum class PadStick {
    Left,
    Right,
}
