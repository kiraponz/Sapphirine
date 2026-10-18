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
}

/** A stick of the 3D Control Pad, which the core does not take yet. */
enum class PadStick {
    Left,
    Right,
}
