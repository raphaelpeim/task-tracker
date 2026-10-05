package com.personal.task_tracker.user;

import com.personal.task_tracker.IntegrationTest;
import com.personal.task_tracker.user.entity.AppUser;
import com.personal.task_tracker.user.enums.UserRole;
import com.personal.task_tracker.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

class UserControllerTest extends IntegrationTest {

	@Autowired
	private UserRepository repository;

	// GET ALL

	@Test
	void shouldReturnEmptyListWhenNoUser() {
		given()
				.when()
				.get("/api/users")
				.then()
				.statusCode(200)
				.body("$", hasSize(0));
	}

	@Test
	void shouldReturnAllUsersWithoutExposingPassword() {
		saveUser("jdoe", "jdoe@mail.com");
		saveUser("asmith", "asmith@mail.com");

		given()
				.when()
				.get("/api/users")
				.then()
				.statusCode(200)
				.body("$", hasSize(2))
				.body("username", containsInAnyOrder("jdoe", "asmith"))
				.body("every { it.id != null }", equalTo(true))
				.body("every { !it.containsKey('password') }", equalTo(true));
	}

	@Test
	void shouldReturnUsersSortedByLastnameThenFirstnameThenUsername() {
		saveUser("Doe", "John", "jdoe");
		saveUser("Adams", "Zoe", "zadams");
		saveUser("Doe", "Anna", "adoe2");
		saveUser("Doe", "Anna", "adoe");

		given()
				.when()
				.get("/api/users")
				.then()
				.statusCode(200)
				.body("username", contains("zadams", "adoe", "adoe2", "jdoe"));
	}

	// GET BY ID

	@Test
	void shouldReturnUserById() {
		AppUser user = saveUser("jdoe", "jdoe@mail.com");

		given()
				.pathParam("id", user.getId())
				.when()
				.get("/api/users/{id}")
				.then()
				.statusCode(200)
				.body("id", equalTo(user.getId().intValue()))
				.body("firstname", equalTo(user.getFirstname()))
				.body("lastname", equalTo(user.getLastname()))
				.body("username", equalTo(user.getUsername()))
				.body("email", equalTo(user.getEmail()))
				.body("$", not(hasKey("password")));
	}

	@Test
	void shouldReturn404WhenUserNotFound() {
		given()
				.pathParam("id", 999999L)
				.when()
				.get("/api/users/{id}")
				.then()
				.statusCode(404)
				.body("detail", equalTo("There is no user with id: 999999"))
				.body("code", equalTo("user.notFound"));
	}

	@Test
	void shouldReturn400WhenUserIdIsNotNumeric() {
		given()
				.when()
				.get("/api/users/abc")
				.then()
				.statusCode(400)
				.contentType("application/problem+json");
	}

	// DATES

	@Test
	void shouldSetUpdatedDateOnlyWhenUserIsModified() {
		AppUser user = saveUser("jdoe", "jdoe@mail.com");
		assertThat(user.getCreatedDate()).isNotNull();
		assertThat(user.getUpdatedDate()).isNull();

		user.setFirstname("Jane");
		AppUser updatedUser = repository.saveAndFlush(user);

		assertThat(updatedUser.getUpdatedDate()).isNotNull();
		assertThat(repository.findById(user.getId()).orElseThrow().getUpdatedDate()).isNotNull();
	}

	private AppUser saveUser(String username, String email) {
		return repository.save(new AppUser("John", "Doe", username, email, "passwordHash", UserRole.USER));
	}

	private void saveUser(String lastname, String firstname, String username) {
		repository.save(new AppUser(firstname, lastname, username, username + "@mail.com", "passwordHash", UserRole.USER));
	}
}
