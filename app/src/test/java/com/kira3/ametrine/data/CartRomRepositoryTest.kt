package com.kira3.ametrine.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CartRomRepositoryTest {
    @Test
    fun onlyTwoMiBIsARomImage() {
        assertTrue(CartRomRepository.isValidSize(2L * 1024 * 1024))
        assertFalse(CartRomRepository.isValidSize(0))
        assertFalse(CartRomRepository.isValidSize(1024 * 1024))
        assertFalse(CartRomRepository.isValidSize(4L * 1024 * 1024))
    }
}
