/*
 * Copyright 2021 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.coverage;

import com.intellij.coverage.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.TestSourcesFilter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.containers.CollectionFactory;
import io.flutter.FlutterBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// com.intellij.coverage.JavaCoverageAnnotator
public class FlutterCoverageAnnotator extends BaseCoverageAnnotator {
  private final Map<String, PackageAnnotator.PackageCoverageInfo> myPackageCoverageInfos = new HashMap<>();
  private final Map<String, PackageAnnotator.PackageCoverageInfo> myFlattenPackageCoverageInfos = new HashMap<>();
  private final Map<VirtualFile, PackageAnnotator.PackageCoverageInfo> myDirCoverageInfos =
    new HashMap<>();
  private final Map<VirtualFile, PackageAnnotator.PackageCoverageInfo> myTestDirCoverageInfos =
    new HashMap<>();
  private final Map<String, PackageAnnotator.ClassCoverageInfo> myClassCoverageInfos = new HashMap<>();
  private final Map<PsiElement, PackageAnnotator.SummaryCoverageInfo> myExtensionCoverageInfos = CollectionFactory.createWeakMap();

  public static FlutterCoverageAnnotator getInstance(Project project) {
    return ServiceManager.getService(project, FlutterCoverageAnnotator.class);
  }

  public FlutterCoverageAnnotator(Project project) {
    super(project);
  }

  @Override
  @Nullable
  public String getDirCoverageInformationString(@NotNull PsiDirectory directory,
                                         @NotNull CoverageSuitesBundle currentSuite,
                                         @NotNull CoverageDataManager coverageDataManager) {
    final PsiPackage psiPackage = JavaDirectoryService.getInstance().getPackage(directory);
    if (psiPackage == null) return null;

    final VirtualFile virtualFile = directory.getVirtualFile();

    final boolean isInTestContent = TestSourcesFilter.isTestSources(virtualFile, directory.getProject());
    if (!currentSuite.isTrackTestFolders() && isInTestContent) {
      return null;
    }
    return getCoverageInformationString(isInTestContent ? myTestDirCoverageInfos.get(virtualFile) : myDirCoverageInfos.get(virtualFile),
                                        coverageDataManager.isSubCoverageActive());
  }

  @Override
  @Nullable
  public String getFileCoverageInformationString(@NotNull PsiFile file,
                                                 @NotNull CoverageSuitesBundle bundle,
                                                 @NotNull CoverageDataManager manager) {
    // TODO Handle top-level functions.
    return null;
  }

  @Override
  @Nullable
  protected Runnable createRenewRequest(@NotNull CoverageSuitesBundle suite, @NotNull CoverageDataManager dataManager) {


    final Project project = getProject();
    final List<PsiPackage> packages = new ArrayList<>();
    final List<PsiClass> classes = new ArrayList<>();

    for (CoverageSuite coverageSuite : suite.getSuites()) {
      final FlutterCoverageSuite flutterSuite = (FlutterCoverageSuite)coverageSuite;
      classes.addAll(flutterSuite.getCurrentSuiteClasses(project));
      packages.addAll(flutterSuite.getCurrentSuitePackages(project));
    }

    if (packages.isEmpty() && classes.isEmpty()) {
      return null;
    }

    return () -> {
      final PackageAnnotator.Annotator annotator = new PackageAnnotator.Annotator() {
        @Override
        public void annotatePackage(String packageQualifiedName, PackageAnnotator.PackageCoverageInfo packageCoverageInfo) {
          myPackageCoverageInfos.put(packageQualifiedName, packageCoverageInfo);
        }

        @Override
        public void annotatePackage(String packageQualifiedName,
                                    PackageAnnotator.PackageCoverageInfo packageCoverageInfo,
                                    boolean flatten) {
          if (flatten) {
            myFlattenPackageCoverageInfos.put(packageQualifiedName, packageCoverageInfo);
          }
          else {
            annotatePackage(packageQualifiedName, packageCoverageInfo);
          }
        }

        @Override
        public void annotateSourceDirectory(VirtualFile dir,
                                            PackageAnnotator.PackageCoverageInfo dirCoverageInfo,
                                            Module module) {
          myDirCoverageInfos.put(dir, dirCoverageInfo);
        }

        @Override
        public void annotateTestDirectory(VirtualFile virtualFile,
                                          PackageAnnotator.PackageCoverageInfo packageCoverageInfo,
                                          Module module) {
          myTestDirCoverageInfos.put(virtualFile, packageCoverageInfo);
        }

        @Override
        public void annotateClass(String classQualifiedName, PackageAnnotator.ClassCoverageInfo classCoverageInfo) {
          myClassCoverageInfos.put(classQualifiedName, classCoverageInfo);
        }
      };
      for (PsiPackage aPackage : packages) {
        new PackageAnnotator(aPackage).annotate(suite, annotator);
      }
      for (final PsiClass aClass : classes) {
        final Runnable runnable = () -> {
          final String packageName = ((PsiClassOwner)aClass.getContainingFile()).getPackageName();
          final PsiPackage psiPackage = JavaPsiFacade.getInstance(project).findPackage(packageName);
          if (psiPackage == null) return;
          new PackageAnnotator(psiPackage).annotateFilteredClass(aClass, suite, annotator);
        };
        ApplicationManager.getApplication().runReadAction(runnable);
      }
      dataManager.triggerPresentationUpdate();
    };
  }

  @Override
  public void onSuiteChosen(CoverageSuitesBundle newSuite) {
    super.onSuiteChosen(newSuite);
    myPackageCoverageInfos.clear();
    myFlattenPackageCoverageInfos.clear();
    myDirCoverageInfos.clear();
    myTestDirCoverageInfos.clear();
    myClassCoverageInfos.clear();
    myExtensionCoverageInfos.clear();
  }

  @Nullable
  public static String getCoverageInformationString(PackageAnnotator.SummaryCoverageInfo info, boolean subCoverageActive) {
    if (info == null) return null;
    if (info.totalClassCount == 0 || info.totalLineCount == 0) return null;
    if (subCoverageActive) {
      return info.coveredClassCount + info.getCoveredLineCount() > 0 ? CoverageBundle.message("coverage.view.text.covered") : null;
    }
    return FlutterBundle.message("coverage.view.text.classes.covered", (int)((double)info.coveredClassCount / info.totalClassCount * 100)) +
           ", " +
           CoverageBundle
             .message("coverage.view.text.lines.covered", (int)((double)info.getCoveredLineCount() / info.totalLineCount * 100));
  }

  @Nullable
  public String getPackageCoverageInformationString(final PsiPackage psiPackage,
                                                    @Nullable final Module module,
                                                    @NotNull final CoverageDataManager coverageDataManager) {
    return getPackageCoverageInformationString(psiPackage, module, coverageDataManager, false);
  }

  @Nullable
  public String getPackageCoverageInformationString(final PsiPackage psiPackage,
                                                    @Nullable final Module module,
                                                    @NotNull final CoverageDataManager coverageDataManager,
                                                    boolean flatten) {
    if (psiPackage == null) return null;
    final boolean subCoverageActive = coverageDataManager.isSubCoverageActive();
    if (module != null) {
      final PsiDirectory[] directories = psiPackage.getDirectories(GlobalSearchScope.moduleScope(module));
      PackageAnnotator.SummaryCoverageInfo result = null;
      for (PsiDirectory directory : directories) {
        final VirtualFile virtualFile = directory.getVirtualFile();
        result = merge(result, myDirCoverageInfos.get(virtualFile));
        result = merge(result, myTestDirCoverageInfos.get(virtualFile));
      }
      return getCoverageInformationString(result, subCoverageActive);
    }
    else {
      final PackageAnnotator.PackageCoverageInfo info = getPackageCoverageInfo(psiPackage, flatten);
      return getCoverageInformationString(info, subCoverageActive);
    }
  }

  @Nullable
  public PackageAnnotator.PackageCoverageInfo getPackageCoverageInfo(@NotNull PsiPackage psiPackage, boolean flattenPackages) {
    final String qualifiedName = psiPackage.getQualifiedName();
    return flattenPackages ? myFlattenPackageCoverageInfos.get(qualifiedName) : myPackageCoverageInfos.get(qualifiedName);
  }

  @Nullable
  public static PackageAnnotator.SummaryCoverageInfo merge(@Nullable final PackageAnnotator.SummaryCoverageInfo info,
                                                           @Nullable final PackageAnnotator.SummaryCoverageInfo testInfo) {
    if (info == null) return testInfo;
    if (testInfo == null) return info;
    final PackageAnnotator.PackageCoverageInfo coverageInfo = new PackageAnnotator.PackageCoverageInfo();
    coverageInfo.totalClassCount = info.totalClassCount + testInfo.totalClassCount;
    coverageInfo.coveredClassCount = info.coveredClassCount + testInfo.coveredClassCount;

    coverageInfo.totalLineCount = info.totalLineCount + testInfo.totalLineCount;
    coverageInfo.coveredLineCount = info.getCoveredLineCount() + testInfo.getCoveredLineCount();

    coverageInfo.totalBranchCount = info.totalBranchCount + testInfo.totalBranchCount;
    coverageInfo.coveredBranchCount = info.coveredBranchCount + testInfo.coveredBranchCount;
    return coverageInfo;
  }

  public String getLineCoveredPercentage(@Nullable PackageAnnotator.SummaryCoverageInfo info) {
    if (info == null) return null;
    return getPercentage(info.getCoveredLineCount(), info.totalLineCount);
  }

  public String getMethodCoveredPercentage(@Nullable PackageAnnotator.SummaryCoverageInfo info) {
    if (info == null) return null;
    return getPercentage(info.coveredMethodCount, info.totalMethodCount);
  }

  public String getClassCoveredPercentage(@Nullable PackageAnnotator.SummaryCoverageInfo info) {
    if (info == null) return null;
    return getPercentage(info.coveredClassCount, info.totalClassCount);
  }

  public String getBranchCoveredPercentage(@Nullable PackageAnnotator.SummaryCoverageInfo info) {
    if (info == null) return null;
    return getPercentage(info.coveredBranchCount, info.totalBranchCount);
  }

  private static String getPercentage(int covered, int total) {
    final int percentage = total == 0 ? 100 : (int)((double)covered / total * 100);
    return percentage + "% (" + covered + "/" + total + ")";
  }

  /**
   * @param classFQName to obtain coverage information for
   * @return human-readable coverage information
   */
  @Nullable
  public @Nls
  String getClassCoverageInformationString(String classFQName, CoverageDataManager coverageDataManager) {
    final PackageAnnotator.ClassCoverageInfo info = myClassCoverageInfos.get(classFQName);
    if (info == null) return null;
    if (info.totalMethodCount == 0 || info.totalLineCount == 0) return null;
    if (coverageDataManager.isSubCoverageActive()) {
      return info.coveredMethodCount + info.fullyCoveredLineCount + info.partiallyCoveredLineCount > 0 ? CoverageBundle
        .message("coverage.view.text.covered") : null;
    }
    return FlutterBundle
             .message("coverage.view.text.methods.covered", (int)((double)info.coveredMethodCount / info.totalMethodCount * 100)) + ", " +
           CoverageBundle.message("coverage.view.text.lines.covered",
                                  (int)((double)(info.fullyCoveredLineCount + info.partiallyCoveredLineCount) / info.totalLineCount * 100));
  }

  @Nullable
  public PackageAnnotator.ClassCoverageInfo getClassCoverageInfo(String classFQName) {
    return myClassCoverageInfos.get(classFQName);
  }
}
