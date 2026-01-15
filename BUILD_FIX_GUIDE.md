# Build Fix Guide for Apache Jena - Faceting Implementation

## Overview
This guide provides step-by-step instructions to fix the build issues preventing testing of the new faceting functionality in the `jena-text` module.

## Current Status

✅ **Completed Work:**
- FacetValue.java - Immutable facet value class
- FacetedTextResults.java - Result container with faceting
- TestFacetedResults.java - Comprehensive test suite (6 tests)
- faceting_methods.txt - Implementation for TextIndexLucene

❌ **Build Issue Blocking Tests:**
- jena-iri3986 module compilation failure
- Missing dependency artifacts (jena-cmds, jena-arq)
- Cannot run Maven tests until fixed

---

## Problem Diagnosis

### Primary Issue: jena-iri3986 Compilation Error

**Error Message:**
```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin3:14.1:compile 
(default-compile) on project jena-iri3986: Fatal error compiling: 
error: release version 21 not supported
```

**Root Cause:** The jena-iri3986 module is trying to use Java 21 features, but the build environment only has Java 17 available.

**Location:** `/workspaces/jena/jena-iri3986/`

---

## Fix Strategy

### Option 1: Skip Problematic Module (Quickest)

This allows building and testing jena-text without fixing jena-iri3986.

#### Step 1: Configure Maven to Skip jena-iri3986

Edit the root `pom.xml`:

```bash
cd /workspaces/jena
nano pom.xml
```

Find the `<modules>` section and comment out jena-iri3986:

```xml
<modules>
    <module>jena-base</module>
    <module>jena-core</module>
    <!-- <module>jena-iri3986</module> -->  <!-- TEMPORARILY DISABLED -->
    <module>jena-text</module>
    <!-- ... other modules ... -->
</modules>
```

#### Step 2: Build Core Dependencies

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Build only the modules jena-text depends on
mvn clean install -pl jena-base,jena-core,jena-arq,jena-shaded-guava -am -DskipTests
```

#### Step 3: Build and Test jena-text

```bash
# Build jena-text module
mvn clean install -pl jena-text -DskipTests

# Run our faceting tests
mvn test -pl jena-text -Dtest=TestFacetedResults
```

**Expected Result:** Tests should compile and run, showing 6 passing tests.

---

### Option 2: Fix jena-iri3986 (Proper Solution)

This fixes the underlying issue properly.

#### Step 1: Install Java 21

```bash
# Update package list
sudo apt update

# Install Java 21
sudo apt install -y openjdk-21-jdk

# Verify installation
ls -la /usr/lib/jvm/
```

#### Step 2: Configure Maven for Multi-Version Build

Create a profile in root `pom.xml` for Java 21 modules:

```xml
<profiles>
    <profile>
        <id>java21-modules</id>
        <activation>
            <jdk>[21,)</jdk>
        </activation>
        <modules>
            <module>jena-iri3986</module>
        </modules>
    </profile>
</profiles>
```

#### Step 3: Build with Appropriate Java Versions

```bash
# Set Java 17 for main build
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# Build everything except Java 21 modules
mvn clean install -DskipTests -pl '!jena-iri3986'

# Switch to Java 21 for that module
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn clean install -pl jena-iri3986 -DskipTests

# Switch back to Java 17 for tests
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
mvn test -pl jena-text -Dtest=TestFacetedResults
```

---

### Option 3: Standalone Test Execution (Development)

For development/verification without full Maven build:

#### Step 1: Compile Our Classes Manually

```bash
cd /workspaces/jena/jena-text/src/main/java

# Create output directory
mkdir -p /tmp/test-classes

# Find jena-core JAR (contains Node, NodeFactory, etc.)
JENA_CORE=$(find ~/.m2/repository -name 'jena-core-*.jar' | head -1)

# Compile our classes
javac -d /tmp/test-classes -cp "$JENA_CORE" \
    org/apache/jena/query/text/FacetValue.java \
    org/apache/jena/query/text/FacetedTextResults.java

# Verify compilation
echo $?  # Should print 0 for success
```

#### Step 2: Create Minimal Test

```bash
cat > /tmp/MinimalTest.java << 'TESTEOF'
import org.apache.jena.query.text.*;
import java.util.*;

public class MinimalTest {
    public static void main(String[] args) {
        // Test FacetValue
        FacetValue fv = new FacetValue("electronics", 42);
        assert fv.getValue().equals("electronics");
        assert fv.getCount() == 42;
        System.out.println("✓ FacetValue works: " + fv);
        
        // Test equality
        FacetValue fv2 = new FacetValue("electronics", 42);
        assert fv.equals(fv2);
        System.out.println("✓ FacetValue equality works");
        
        System.out.println("\n✅ All basic tests passed!");
    }
}
TESTEOF

# Compile and run
javac -d /tmp/test-classes -cp "$JENA_CORE:/tmp/test-classes" /tmp/MinimalTest.java
java -cp "$JENA_CORE:/tmp/test-classes" -ea MinimalTest
```

**Note:** This only tests FacetValue (no dependencies). FacetedTextResults requires TextHit which needs full Jena.

---

## Verification Checklist

Once you've applied a fix, verify with:

```bash
# 1. Check compilation
cd /workspaces/jena/jena-text
mvn clean compile
# Should see: BUILD SUCCESS

# 2. Run tests
mvn test -Dtest=TestFacetedResults
# Should see: Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

# 3. Verify test output
cat target/surefire-reports/org.apache.jena.query.text.TestFacetedResults.txt
# Should show all tests passing
```

---

## Expected Test Results

When tests run successfully, you should see:

```
-------------------------------------------------------
 T E S T S
-------------------------------------------------------
Running org.apache.jena.query.text.TestFacetedResults
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.xyz sec

Results :

Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

[INFO] BUILD SUCCESS
```

**Test Methods:**
1. `testFacetValueBasics()` - Basic getter functionality
2. `testFacetValueEquality()` - equals/hashCode contract  
3. `testFacetedTextResultsBasics()` - Container functionality
4. `testFacetedTextResultsGetFacetsForField()` - Field accessor
5. `testFacetedTextResultsImmutability()` - Defensive copying
6. `testFacetedTextResultsToString()` - Debug output

---

## Troubleshooting

### Error: "Cannot find symbol: class TextHit"

**Solution:** TextHit must be compiled first. Build jena-text base classes:

```bash
mvn compile -pl jena-text
```

### Error: "package org.apache.jena.graph does not exist"

**Solution:** jena-core not in classpath. Build it:

```bash
mvn install -pl jena-core -DskipTests
```

### Error: "Maven requires Java 17 or newer"

**Solution:** Set JAVA_HOME explicitly:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
java -version  # Verify: should show "17.x.x"
```

### Tests Compile but Don't Run

**Solution:** Check test is in correct directory:

```bash
ls -la jena-text/src/test/java/org/apache/jena/query/text/TestFacetedResults.java
# File should exist with 152 lines
```

---

## Persistent Configuration

To avoid setting JAVA_HOME every time:

```bash
# Add to ~/.bashrc
echo 'export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64' >> ~/.bashrc
echo 'export PATH=$JAVA_HOME/bin:$PATH' >> ~/.bashrc

# Reload
source ~/.bashrc
```

---

## Summary

**Recommended Approach:** Option 1 (Skip jena-iri3986)
- **Time Required:** 10-15 minutes
- **Difficulty:** Easy
- **Risk:** Low (isolated to build config)

**Alternative:** Option 2 (Fix properly)
- **Time Required:** 30-45 minutes
- **Difficulty:** Moderate
- **Risk:** Medium (affects multiple modules)

**Quick Verification:** Option 3
- **Time Required:** 5 minutes
- **Difficulty:** Easy
- **Risk:** Minimal (temporary test)

---

## Support

If issues persist:

1. Check Java version: `java -version` (should be 17.x)
2. Check Maven version: `mvn -version` (should be 4.x)
3. Clean everything: `mvn clean` and `rm -rf ~/.m2/repository/org/apache/jena`
4. Start fresh with Option 1

---

## Next Steps After Fix

Once tests pass:

1. ✅ Verify all 6 tests pass
2. 📝 Integrate `queryWithFacets$()` into TextIndexLucene.java
3. 🧪 Add integration tests with real Lucene index
4. 📊 Performance benchmarking
5. 📖 Update documentation

---

**Created:** 2026-01-15
**Status:** Ready for execution
**Author:** Faceting Implementation Team
