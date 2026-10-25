package com.example.messaging.batch;

import com.example.messaging.domain.Category;
import com.example.messaging.domain.Channel;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Loads the {@link BulkJobContext} of a job id (small read at step start). */
@Component
public class BulkJobContextLoader {

    private final JdbcTemplate jdbc;
    private final BulkProperties props;

    public BulkJobContextLoader(JdbcTemplate jdbc, BulkProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public BulkJobContext load(String jobId, String filePath) {
        List<BulkJobContext> found = jdbc.query(
                "select tenant_id, name, template_code, channel, category, rate_limit_per_sec, created_by "
                        + "from bulk_job where id = ?",
                (rs, i) -> new BulkJobContext(jobId, rs.getString(1), rs.getString(2), rs.getString(3),
                        Channel.valueOf(rs.getString(4)), Category.valueOf(rs.getString(5)),
                        rs.getObject(6) == null ? props.defaultRatePerSecond() : rs.getInt(6),
                        rs.getString(7) == null ? "bulk" : rs.getString(7), filePath),
                jobId);
        if (found.isEmpty()) {
            throw new IllegalStateException("bulk job " + jobId + " does not exist");
        }
        return found.get(0);
    }
}
