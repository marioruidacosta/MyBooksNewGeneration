package com.mcosta.mybooks.data

import com.mcosta.mybooks.data.db.ChallengeDao
import com.mcosta.mybooks.data.db.ChallengeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [ChallengeDao]. */
class FakeChallengeDao : ChallengeDao {
    val rows = MutableStateFlow<Map<Int, ChallengeEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ChallengeEntity>> = rows.map { it.values.filter { c -> !c.deleted }.sortedByDescending { c -> c.year } }
    override suspend fun getAll() = rows.value.values.filter { !it.deleted }.sortedByDescending { it.year }
    override suspend fun getAllAny() = rows.value.values.sortedByDescending { it.year }
    override suspend fun getByYearAny(year: Int) = rows.value[year]
    override suspend fun upsert(challenge: ChallengeEntity) {
        rows.value = rows.value + (challenge.year to challenge)
    }
    override suspend fun delete(year: Int) {
        rows.value = rows.value - year
    }
}
