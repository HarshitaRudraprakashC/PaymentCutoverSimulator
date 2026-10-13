package org.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;


/** The single rollout setting: what percentage of merchants go to the NEW processor. */
@Repository
@RequiredArgsConstructor
public class RolloutRepository {

    private final JdbcTemplate jdbc;

    public int newPercent() {
        return jdbc.queryForObject("SELECT new_percent FROM rollout_config WHERE id = 1", Integer.class);
    }

    public void setNewPercent(int percent) {
        jdbc.update("UPDATE rollout_config SET new_percent = ?, updated_at = now() WHERE id = 1", percent);
    }
}
