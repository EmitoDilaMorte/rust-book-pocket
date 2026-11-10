package dev.rustbook.pocket.runner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHeuristicsTest {

    @Test
    fun `std-only snippets can run locally`() {
        val code = """
            fn main() {
                let mut names = vec!["Ferris"];
                names.push("Rust");
                for name in &names {
                    println!("{name}");
                }
            }
        """.trimIndent()

        assertFalse(CodeHeuristics.requiresExternalCrates(code))
    }

    @Test
    fun `detects external crate imports`() {
        assertTrue(CodeHeuristics.requiresExternalCrates("use rand::Rng;\nfn main() {}"))
        assertTrue(CodeHeuristics.requiresExternalCrates("extern crate trpl;\nfn main() {}"))
        assertTrue(CodeHeuristics.requiresExternalCrates("fn main() { rand::thread_rng(); }"))
    }

    @Test
    fun `ignores crate names in comments and strings`() {
        val code = """
            // use rand::Rng is not needed here
            fn main() {
                println!("use rand::Rng");
            }
        """.trimIndent()

        assertFalse(CodeHeuristics.requiresExternalCrates(code))
    }

    @Test
    fun `ignores crate names inside block comments`() {
        val code = """
            /* rand::thread_rng() would need a dependency */
            fn main() {}
        """.trimIndent()

        assertFalse(CodeHeuristics.requiresExternalCrates(code))
    }

    @Test
    fun `detects crates used through raw strings only when they are code`() {
        val code = "fn main() { println!(r#\"rand::thread_rng()\"#); }"
        assertFalse(CodeHeuristics.requiresExternalCrates(code))
    }
}
