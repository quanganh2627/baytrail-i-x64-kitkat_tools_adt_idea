/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.android.inspections.lint;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import lombok.ast.CompilationUnit;
import lombok.ast.Node;
import lombok.ast.grammar.Source;
import lombok.ast.printer.SourcePrinter;
import lombok.ast.printer.StructureFormatter;
import lombok.ast.printer.TextFormatter;
import org.jetbrains.android.AndroidTestCase;

import java.util.List;

import static org.jetbrains.android.inspections.lint.LombokPsiConverter.SKIP_UNUSED_NODES;

public class LombokPsiConverterTest extends AndroidTestCase {
  /**
   * Check that AST positions are okay? This works by comparing the
   * offsets of each AST node with the positions in the corresponding
   * offsets in an AST generated with the Parboiled Java parser. There are
   * a lot of individual differences in these two files; it's not clear
   * whether a method should include its javadoc range etc -- so these
   * tests aren't expected to pass, but the diff is useful to inspect
   * the position ranges of AST nodes, and fill out the missing ones etc.
   * (There are quite a few missing ones right now; the focus has been
   * on adding the ones that Lint will actually look up and use.)
   */
  private static final boolean CHECK_POSITIONS = false;

  // TODO:
  // Test interface body, test enum body, test annotation body!

  /** Include varargs in the test (currently fails, but isn't used by lint) */
  private static final boolean INCLUDE_VARARGS = false;

  public void testPsiToLombokConversion1() {
    VirtualFile file = myFixture.copyFileToProject("intentions/R.java", "src/p1/p2/R.java");
    check(file);
  }

  public void testPsiToLombokConversion2() {
    VirtualFile file = myFixture.copyFileToProject("intentions/R.java", "src/p1/p2/R.java");
    check(file);
  }

  public void testPsiToLombokConversion3() {
    // This code is formatted a bit strangely; this is done such that it matches how the
    // code is printed *back* by Lombok's AST pretty printer, so we can diff the two.
    String testClass =
      "package p1.p2;\n" +
      "\n" +
      // Imports
      "import java.util.List;\n" +
      "import java.util.regexp.*;\n" +
      "import static java.util.Arrays.asList;\n" +
      "\n" +
      "public final class R2<K,V> {\n" +
      "    int myField1;\n" +
      "    \n" +
      "    final int myField2 = 42;\n" +
      "    \n" +
      "    // Comments and extra whitespace gets stripped\n" +
      "    \n" +
      "    private static final int CONSTANT = 42;\n" +
      "    private int[] foo1 = new int[5];\n" +
      "    private int[] foo2 = new int[] {1};\n" +
      "    private static int myRed = android.R.color.red;\n" +
      "    \n" +
      // Constructors
      "    public R2(int x,List list) {\n" +
      // Method invocations
      "        System.out.println(list.size());\n" +
      "        System.out.println(R2.drawable.s2);\n" +
      "    }\n" +
      "    \n" +
      // Methods
      "    @Override\n" +
      "    @SuppressWarnings({\"foo1\", \"foo2\"})\n" +
      //"    @android.annotation.SuppressLint({\"foo1\",\"foo2\"})\n" +
      //"    @android.annotation.TargetApi(value=5})\n" +
      "    public void myMethod1(List list) {\n" +
      "    }\n" +
      "    \n" +
      "    public int myMethod2() {\n" +
      "        return 42;\n" +
      "    }\n" +
      "    \n" +
      // Misc
      "    private void myvarargs(int" + (INCLUDE_VARARGS ? "..." : "[]") + " x) {\n" +
      "        Collections.<Map<String,String>>emptyMap();\n" +
      "    }\n" +
      "    \n" +
      "    private void myvarargs2(java.lang.String" + (INCLUDE_VARARGS ? "..." : "[]") + " x) {\n" +
      "    }\n" +
      "    \n" +
      "    private void myarraytest(String[][] args, int index) {\n" +
      "        int y = args[5][index + 1];\n" +
      "    }\n" +
      "    \n" +
      "    private void controlStructs(\n" +
      "    @SuppressWarnings(\"all\")\n" +
      "    int myAnnotatedArg) {\n" +
      "        boolean x = false;\n" +
      "        int y = 4, z = 5, w;\n" +
      "        if (x) {\n" +
      "            System.out.println(\"Ok\");\n" +
      "        }\n" +
      "        if (x) {\n" +
      "            System.out.println(\"Ok\");\n" +
      "        } else {\n" +
      "            System.out.println(\"Not OK\");\n" +
      "        }\n" +
      "        String[] args = new String[] {\"test1\", \"test2\"};\n" +
      "        for (String arg : args) {\n" +
      "            System.out.println(arg);\n" +
      "        }\n" +
      "        for (int i = 0, n = args.length; i < n; i++, i--, i++) {\n" +
      "            y++;\n" +
      "            --z;\n" +
      "            w = y;\n" +
      "            x = !x;\n" +
      "            if (w == 2) {\n" +
      "                continue;\n" +
      "            }\n" +
      "        }\n" +
      "        switch (y) {\n" +
      "        case 1:\n" +
      "            {\n" +
      "                x = false;\n" +
      "                break;\n" +
      "            }\n" +
      "        case 2:\n" +
      "            {\n" +
      "            }\n" +
      "        }\n" +
      "        synchronized (this) {\n" +
      "            x = false;\n" +
      "        }\n" +
      "        w = y + z;\n" +
      "        w = y - z;\n" +
      "        w = y * z;\n" +
      "        w = y / z;\n" +
      "        w = y % z;\n" +
      "        w = y ^ z;\n" +
      "        w = y & z;\n" +
      "        w = y | z;\n" +
      "        w = y << z;\n" +
      "        w = y >> z;\n" +
      "        w = y >>> z;\n" +
      "        f = y < z;\n" +
      "        f = y <= z;\n" +
      "        f = y > z;\n" +
      "        f = y >= z;\n" +
      "        y++;\n" +
      "        y--;\n" +
      "        ++y;\n" +
      "        --y;\n" +
      "        y += 1;\n" +
      "        y -= 1;\n" +
      "        y *= 1;\n" +
      "        y /= 1;\n" +
      "        y %= 1;\n" +
      "        y <<= 1;\n" +
      "        y >>= 1;\n" +
      "        y >>>= 1;\n" +

      "        f = f && x;\n" +
      "        f = f || x;\n" +
      "        f = !f;\n" +
      "        f = y != z;\n" +
      "        y = -y;\n" +
      "        y = +y;\n" +
      // We're stripping parentheses from the Lombok AST:
      //"        y = (y + z) * w;\n" +
      "        y = x ? y : w;\n" +
      "\n" +
      // Anonymous inner class
      "        Runnable r = new Runnable() {\n" +
      "          @Override\n" +
      "          public void run() {\n" +
      "            System.out.println(\"Test\");\n" +
      "          }\n" +
      "        };\n" +
      "\n" +
      "    }\n" +
      "    \n" +
      // Innerclass
      "    public static final class drawable {\n" +
      // Check literals
      "        public static String s = \"This is a test\";\n" +
      "        \n" +
      "        public static int s2 = 42;\n" +
      "        \n" +
      "        public static int s2octal = 042;\n" +
      "        \n" +
      "        public static int s2hex = 0x42;\n" +
      "        \n" +
      "        public static long s3 = 42L;\n" +
      "        \n" +
      "        public static double s4 = 3.3;\n" +
      "        \n" +
      "        public static float s5 = 3.2e5f;\n" +
      "        \n" +
      "        public static char s6 = 'x';\n" +
      "        \n" +
      "        public static int icon = 0x7f020000;\n" +
      "        \n" +
      "        public static double s7 = -3.3;\n" +
      "        \n" +
      "        public static int s8 = -1;\n" +
      "        \n" +
      "        public static char s9 = 'a';\n" +
      "    }\n" +
      "}";
    check(testClass, "src/p1/p2/R2.java");
  }

  public void testPsiToLombokConversion4() {
    String testClass =
      "package test.pkg;\n" +
      "\n" +
      "import android.annotation.SuppressLint;\n" +
      "import android.annotation.TargetApi;\n" +
      "import android.os.Build;\n" +
      "import org.w3c.dom.DOMError;\n" +
      "import org.w3c.dom.DOMErrorHandler;\n" +
      "import org.w3c.dom.DOMLocator;\n" +
      "\n" +
      "import android.view.ViewGroup.LayoutParams;\n" +
      "import android.app.Activity;\n" +
      "import android.app.ApplicationErrorReport;\n" +
      "import android.app.ApplicationErrorReport.BatteryInfo;\n" +
      "import android.graphics.PorterDuff;\n" +
      "import android.graphics.PorterDuff.Mode;\n" +
      "import android.widget.Chronometer;\n" +
      "import android.widget.GridLayout;\n" +
      "import dalvik.bytecode.OpcodeInfo;\n" +
      "\n" +
      "public class ApiCallTest extends Activity {\n" +
      "    @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)\n" +
      "    public void method(Chronometer chronometer, DOMLocator locator) {\n" +
      "        String s = \"/sdcard/fyfaen\";\n" +
      "        // Virtual call\n" +
      "        getActionBar(); // API 11\n" +
      "\n" +
      "        // Class references (no call or field access)\n" +
      "        DOMError error = null; // API 8\n" +
      "        Class<?> clz = DOMErrorHandler.class; // API 8\n" +
      "\n" +
      "        // Method call\n" +
      "        chronometer.getOnChronometerTickListener(); // API 3\n" +
      "\n" +
      "        // Inherited method call (from TextView\n" +
      "        chronometer.setTextIsSelectable(true); // API 11\n" +
      "\n" +
      "        // Field access\n" +
      "        int field = OpcodeInfo.MAXIMUM_VALUE; // API 11\n" +
      "        int fillParent = LayoutParams.FILL_PARENT; // API 1\n" +
      "        // This is a final int, which means it gets inlined\n" +
      "        int matchParent = LayoutParams.MATCH_PARENT; // API 8\n" +
      "        // Field access: non final\n" +
      "        BatteryInfo batteryInfo = getReport().batteryInfo;\n" +
      "\n" +
      "        // Enum access\n" +
      "        Mode mode = PorterDuff.Mode.OVERLAY; // API 11\n" +
      "    }\n" +
      "\n" +
      "    // Return type\n" +
      "    GridLayout getGridLayout() { // API 14\n" +
      "        return null;\n" +
      "    }\n" +
      "\n" +
      "    private ApplicationErrorReport getReport() {\n" +
      "        return null;\n" +
      "    }\n" +
      "}\n";

    // Parse the above file as a PSI datastructure
    PsiFile file = myFixture.addFileToProject("src/test/pkg/ApiCallTest.java", testClass);
    check(file, testClass);
  }

  public void testPsiToLombokConversion5() {
    String testClass =
      "package test.pkg;\n" +
      "\n" +
      "/* This stub is for using by IDE only. It is NOT the Manifest class actually packed into APK */\n" +
      "public final class Manifest {\n" +
      "}";
    PsiFile file = myFixture.addFileToProject("src/test/pkg/Manifest.java", testClass);
    check(file, testClass);
  }

  public void testPsiToLombokConversion6() {
    String testClass =
      "package test.pkg;\n" +
      "import java.util.HashMap;\n" +
      "\n" +
      "/* This stub is for using by IDE only. It is NOT the Manifest class actually packed into APK */\n" +
      "public final class Wildcards {\n" +
      "  HashMap<Integer, Integer> s4 = new HashMap<Integer, Integer>();\n" +
      "}";
    PsiFile file = myFixture.addFileToProject("src/test/pkg/Wildcards.java", testClass);
    check(file, testClass);
  }

  public void testPsiToLombokConversion7() {
    String testClass =
      "package test.pkg;\n" +
      "import java.util.HashMap;\n" +
      "\n" +
      "/* This stub is for using by IDE only. It is NOT the Manifest class actually packed into APK */\n" +
      "public final class R3<K, V> {\n" +
      "  HashMap<Integer, Map<String, Integer>> s1 = new HashMap<Integer, Map<String, Integer>>();\n" +
      "  Map<Map<String[], List<Integer[]>>,List<String[]>>[] s2;\n" +
      "  Map<Integer, Map<String, List<Integer>>> s3;\n" +
      "  int[][] s4;\n" +
      "}";
    PsiFile file = myFixture.addFileToProject("src/test/pkg/R3.java", testClass);
    check(file, testClass);
  }

  // This test currently fails; need to tweak handling of whitespace around type parameters
  //public void testPsiToLombokConversion8() {
  //  String testClass =
  //    "package test.pkg;\n" +
  //    "import java.util.HashMap;\n" +
  //    "\n" +
  //    "/* This stub is for using by IDE only. It is NOT the Manifest class actually packed into APK */\n" +
  //    "public final class R4 {\n" +
  //    "    Object o = Collections.<Map<String,String>>emptyMap();\n" +
  //    "    Object o2 = Collections.< Map < String , String>>>emptyMap();\n" +
  //    "}";
  //  PsiFile file = myFixture.addFileToProject("src/test/pkg/R4.java", testClass);
  //  check(file, testClass);
  //}

  public void testPsiToLombokConversion9() {
    String testClass =
      "package test.pkg;\n" +
      "\n" +
      "public final class R5 {\n" +
      "    public void foo() {\n" +
      "        setTitleColor(android.R.color.black);\n" +
      "        setTitleColor(R.color.black);\n" +
      "    }\n" +
      "}";
    PsiFile file = myFixture.addFileToProject("src/test/pkg/R5.java", testClass);
    check(file, testClass);
  }

  public void testPsiToLombokConversion10() {
    // Checks that annotations on variable declarations are preserved
    String testClass =
      "package test.pkg;\n" +
      "\n" +
      "import android.annotation.SuppressLint;\n" +
      "import android.annotation.TargetApi;\n" +
      "import android.os.Build;\n" +
      "\n" +
      "public class SuppressTest {\n" +
      "    @SuppressLint(\"ResourceAsColor\")\n" +
      "    @TargetApi(Build.VERSION_CODES.HONEYCOMB)\n" +
      "    private void test() {\n" +
      "        @SuppressLint(\"SdCardPath\") String s = \"/sdcard/fyfaen\";\n" +
      "        setTitleColor(android.R.color.black);\n" +
      "    }\n" +
      "\n" +
      "    private void setTitleColor(int color) {\n" +
      "        //To change body of created methods use File | Settings | File Templates.\n" +
      "    }\n" +
      "}\n";
    PsiFile file = myFixture.addFileToProject("src/test/pkg/SuppressTest.java", testClass);
    check(file, testClass);
  }

  private void check(VirtualFile file) {
    assertNotNull(file);
    assertTrue(file.exists());
    Project project = getProject();
    assertNotNull(project);
    PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
    assertNotNull(psiFile);
    check(psiFile, psiFile.getText());;
  }

  private void check(String source, String relativePath) {
    PsiFile file = myFixture.addFileToProject(relativePath, source);
    check(file, source);
  }

  private void check(PsiFile psiFile, String source) {
    assertTrue(psiFile.getClass().getName(), psiFile instanceof PsiJavaFile);
    PsiJavaFile psiJavaFile = (PsiJavaFile)psiFile;
    CompilationUnit node = LombokPsiConverter.convert(psiJavaFile);
    assertNotNull(node);

    String actualStructure;
    if (CHECK_POSITIONS) {
      StructureFormatter structureFormatter = StructureFormatter.formatterWithPositions();
      node.accept(new SourcePrinter(structureFormatter));
      actualStructure = structureFormatter.finish();
    }

    TextFormatter formatter = new TextFormatter();
    node.accept(new SourcePrinter(formatter));
    String actual = formatter.finish();

    if (SKIP_UNUSED_NODES) {
      source = source.replaceAll("\\bpublic\\b", "");
      source = source.replaceAll("\\bprotected\\b", "");
      source = source.replaceAll("\\bprivate\\b", "");
      source = source.replaceAll("\\babstract\\b", "");
      source = source.replaceAll("\\bfinal\\b", "");

      // Remove static, but leave import static alone
      source = source.replaceAll("\\bimport static\\b", "<<IMPORT STATIC>>");
      source = source.replaceAll("\\bstatic\\b", "");
      source = source.replaceAll("<<IMPORT STATIC>>", "import static");
    }

    Source s = new Source(source, "filename");
    List<Node> nodes = s.getNodes();
    assertEquals(1, nodes.size());
    Node expectedNode = nodes.get(0);

    if (CHECK_POSITIONS) {
      StructureFormatter structureFormatter = StructureFormatter.formatterWithPositions();
      expectedNode.accept(new SourcePrinter(structureFormatter));
      String masterStructure = structureFormatter.finish();
      assertEquals(masterStructure, actualStructure);
    }

    formatter = new TextFormatter();
    expectedNode.accept(new SourcePrinter(formatter));
    String master = formatter.finish();
    assertEquals(master, actual);
  }

  // TODO: Iterate over a large body of Java files and run all through the PSI converter
  // to flush out any remaining issues with unexpected constructs etc.
}