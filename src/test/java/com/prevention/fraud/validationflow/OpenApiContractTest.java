package com.prevention.fraud.validationflow;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The committed openapi.yaml must equal the spec generated from the code, and must be a valid OpenAPI document.
 * Regenerate with: mvn test -Dtest=OpenApiContractTest -Dopenapi.write=true
 */
@SpringBootTest(properties = { "app.security.api-keys[0].key=k", "app.security.api-keys[0].tenant-id=t",
		"app.security.api-keys[0].scopes=flow:read" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

	private static final Path FILE = Path.of("openapi.yaml");

	@Autowired
	MockMvc mvc;

	@Test
	void committedSpecMatchesGeneratedAndIsValid() throws Exception {
		String generated = mvc.perform(get("/v3/api-docs.yaml").header("X-API-Key", "k")).andReturn().getResponse()
				.getContentAsString();
		if (Boolean.getBoolean("openapi.write")) {
			Files.writeString(FILE, generated);
		}
		assertTrue(Files.exists(FILE), "openapi.yaml missing; run with -Dopenapi.write=true");
		assertEquals(generated.replace("\r\n", "\n"), Files.readString(FILE).replace("\r\n", "\n"),
				"openapi.yaml is stale; regenerate with -Dopenapi.write=true");
		var opts = new ParseOptions();
		opts.setResolve(true);
		var result = new OpenAPIV3Parser().readContents(generated, null, opts);
		assertTrue(result.getMessages().isEmpty(), () -> "invalid OpenAPI: " + result.getMessages());
		assertNotNull(result.getOpenAPI());
		assertEquals(14, result.getOpenAPI().getPaths().values().stream().mapToInt(p -> p.readOperations().size()).sum());
	}

}
