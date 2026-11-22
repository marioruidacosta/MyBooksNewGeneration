package com.mcosta.mybooks.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BottomTabsTest {
    @Test
    fun theBarEndsWithMore() {
        assertEquals(listOf("Home", "Search", "New books", "Community", "More"), bottomTabs(community = true).map { it.label })
    }

    @Test
    fun withoutTheCommunityItsTabIsGone() {
        assertEquals(listOf("Home", "Search", "New books", "More"), bottomTabs(community = false).map { it.label })
    }

    @Test
    fun theCommunityRouteOpensOnlyWhereTheCommunityExists() {
        kotlin.test.assertFalse(canOpenRoute(Routes.ACTIVITY, community = false))
        kotlin.test.assertTrue(canOpenRoute(Routes.ACTIVITY, community = true))
        kotlin.test.assertTrue(canOpenRoute(Routes.RELEASES, community = false))
    }
}
