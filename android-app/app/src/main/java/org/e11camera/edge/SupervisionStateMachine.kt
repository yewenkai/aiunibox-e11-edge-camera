package org.e11camera.edge

/**
 * 根据低频人体检测结果判断“有人陪护 / 未确认陪护”。
 *
 * 通用 person 模型不能确认年龄，也不能因为某个人位于某个区域就断言那是宝宝。
 * 因此重点监测区域完全不参与陪护判断：只有两人及以上，或出现成人体型证据时，
 * 才抑制外部通知；其余情况一律保持通知武装。
 */
class SupervisionStateMachine(
    private val voteWindow: Int = 4,
    private val confirmVotes: Int = 3,
    private val caregiverLeaveMs: Long = 20_000L
) {
    enum class State {
        UNKNOWN,
        CAREGIVER_PRESENT
    }

    data class Observation(
        val totalPeople: Int,
        val adultLikePeople: Int = 0
    ) {
        val caregiverEvidence: Boolean
            get() = totalPeople >= 2 || adultLikePeople > 0
    }

    private val caregiverVotes = ArrayDeque<Boolean>()
    private var lastCaregiverEvidenceAt = 0L

    var state: State = State.UNKNOWN
        private set

    /** 只有确认有人陪护时暂停推送；UNKNOWN 按安全侧继续推送。 */
    val notificationsSuppressed: Boolean
        get() = state == State.CAREGIVER_PRESENT

    @Synchronized
    fun push(observation: Observation, now: Long): State {
        val caregiverEvidence = observation.caregiverEvidence

        pushVote(caregiverVotes, caregiverEvidence)
        if (caregiverEvidence) lastCaregiverEvidenceAt = now

        val caregiverConfirmed = caregiverVotes.count { it } >= confirmVotes
        state = when {
            caregiverConfirmed -> State.CAREGIVER_PRESENT
            state == State.CAREGIVER_PRESENT &&
                lastCaregiverEvidenceAt > 0 &&
                now - lastCaregiverEvidenceAt < caregiverLeaveMs -> State.CAREGIVER_PRESENT
            else -> State.UNKNOWN
        }
        return state
    }

    @Synchronized
    fun reset() {
        caregiverVotes.clear()
        lastCaregiverEvidenceAt = 0L
        state = State.UNKNOWN
    }

    private fun pushVote(queue: ArrayDeque<Boolean>, value: Boolean) {
        queue.addLast(value)
        while (queue.size > voteWindow) queue.removeFirst()
    }
}
