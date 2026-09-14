import com.personal.task_tracker.models.enums.Priority;
import com.personal.task_tracker.models.enums.Status;
import com.personal.task_tracker.models.enums.Type;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
public class Task {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name="title", length=50, nullable=false)
	private String title;

	@Column(name="description", nullable=false)
	private String description;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name="type", length=9, nullable=false)
	private Type type;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name="status", length=11, nullable=false)
	private Status status;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.NAMED_ENUM)
	@Column(name="priority", length=8, nullable=false)
	private Priority priority;

	@Column(name="assignee", length=50)
	private String assignee; // TODO Link with User table

	@CreationTimestamp
	@Column(name="created_date", nullable=false, updatable=false)
	private LocalDateTime createdDate;

	@UpdateTimestamp
	@Column(name="updated_date")
	private LocalDateTime updatedDate;

	// other fields, getters and setters
}