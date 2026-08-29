package dev.nuclr.plugin.core.panel.github.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class SourceNodeTest {

	@Test
	void createsMissingParentsAndFindsNestedFiles() {
		SourceNode root = new SourceNode("", "", true);
		SourceNode file = new SourceNode("src/main/App.java", "App.java", false);

		root.put(file.getPath(), file);

		assertNotNull(root.find("src"));
		assertNotNull(root.find("src/main"));
		assertEquals(file, root.find("src/main/App.java"));
		assertNull(root.find("src/test/Missing.java"));
	}

	@Test
	void sortsDirectoriesBeforeFilesThenByName() {
		SourceNode root = new SourceNode("", "", true);
		root.put("z.txt", new SourceNode("z.txt", "z.txt", false));
		root.ensureDirectory("Beta");
		root.ensureDirectory("alpha");
		root.put("A.txt", new SourceNode("A.txt", "A.txt", false));

		assertEquals(
				List.of("alpha", "Beta", "A.txt", "z.txt"),
				root.sortedChildren().stream().map(SourceNode::getName).toList());
	}
}
