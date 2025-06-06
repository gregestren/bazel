// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcLinkingContextApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.LinkerInputApi;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.LinkstampApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator.Symbol;

/** Structure of CcLinkingContext. */
public class CcLinkingContext implements CcLinkingContextApi<Artifact> {
  public static final CcLinkingContext EMPTY =
      builder().setExtraLinkTimeLibraries(ExtraLinkTimeLibraries.EMPTY).build();

  /** A list of link options contributed by a single configured target/aspect. */
  @Immutable
  public static final class LinkOptions {
    private final ImmutableList<String> linkOptions;

    // This needs to be here to satisfy two constraints:
    // 1. We cannot use reference equality so that when this object is serialized and then
    // de-serialized, equality still works
    // 2. Link options created from different configured targets but with the same contents must
    // not be equal. If they were, the following error case would happen: if A depends on B1 and C1
    // B1 depends on B2, C1 depends on C2 and B2 and C2 both have "-l<something>" in their linkopts,
    // the nested set containing the linkopts would remove one of them, thereby moving
    // "-l<something>" before the object files of C2 on the linker command line, thus making the
    // symbols in them invisible from C2.
    private final Object symbolForEquality;

    private LinkOptions(ImmutableList<String> linkOptions, Object symbolForEquality) {
      this.linkOptions = Preconditions.checkNotNull(linkOptions);
      this.symbolForEquality = Preconditions.checkNotNull(symbolForEquality);
    }

    public ImmutableList<String> get() {
      return linkOptions;
    }

    public static LinkOptions of(ImmutableList<String> linkOptions, Symbol<?> symbol) {
      return new LinkOptions(linkOptions, symbol);
    }

    @Override
    public int hashCode() {
      // Symbol is sufficient for equality check.
      return symbolForEquality.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof LinkOptions that)) {
        return false;
      }
      if (!this.symbolForEquality.equals(that.symbolForEquality)) {
        return false;
      }
      if (this.linkOptions.equals(that.linkOptions)) {
        return true;
      }
      BugReport.sendBugReport(
          new IllegalStateException(
              "Unexpected inequality with equal symbols: " + this + ", " + that));
      return false;
    }

    @Override
    public String toString() {
      return '[' + Joiner.on(",").join(linkOptions) + "] (owner: " + symbolForEquality;
    }
  }

  /**
   * A linkstamp that also knows about its declared includes.
   *
   * <p>This object is required because linkstamp files may include other headers which will have to
   * be provided during compilation.
   */
  @Immutable
  public static final class Linkstamp implements LinkstampApi<Artifact> {
    private final Artifact artifact;
    private final NestedSet<Artifact> declaredIncludeSrcs;
    private final int nestedDigest;

    // TODO(janakr): if action key context is not available, the digest can be computed lazily,
    // only if we are doing an equality comparison and artifacts are equal. That should never
    // happen, so doing an expensive digest should be ok then. If this is ever moved to Starlark
    // and Starlark doesn't support custom equality or amortized deep equality of nested sets, a
    // Symbol can be used as an equality proxy, similar to what LinkOptions does above.
    Linkstamp(
        Artifact artifact,
        NestedSet<Artifact> declaredIncludeSrcs,
        ActionKeyContext actionKeyContext)
        throws CommandLineExpansionException, InterruptedException {
      this.artifact = Preconditions.checkNotNull(artifact);
      this.declaredIncludeSrcs = Preconditions.checkNotNull(declaredIncludeSrcs);
      StringBuilder nestedDigestBuilder = new StringBuilder();
      for (Artifact declaredIncludeSrc : declaredIncludeSrcs.toList()) {
        nestedDigestBuilder.append(declaredIncludeSrc.getExecPathString());
      }
      nestedDigest = nestedDigestBuilder.toString().hashCode();
    }

    /** Returns the linkstamp artifact. */
    public Artifact getArtifact() {
      return artifact;
    }

    @Override
    public Artifact getArtifactForStarlark(StarlarkThread thread) throws EvalException {
      CcModule.checkPrivateStarlarkificationAllowlist(thread);
      return artifact;
    }

    /** Returns the declared includes. */
    public NestedSet<Artifact> getDeclaredIncludeSrcs() {
      return declaredIncludeSrcs;
    }

    @Override
    public Depset getDeclaredIncludeSrcsForStarlark(StarlarkThread thread) throws EvalException {
      CcModule.checkPrivateStarlarkificationAllowlist(thread);
      return Depset.of(Artifact.class, getDeclaredIncludeSrcs());
    }

    @Override
    public int hashCode() {
      // Artifact should be enough to disambiguate basically all the time.
      return artifact.hashCode();
    }

    @Override
    public final boolean isImmutable() {
      return true; // immutable and Starlark-hashable
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof Linkstamp other)) {
        return false;
      }
      return artifact.equals(other.artifact) && nestedDigest == other.nestedDigest;
    }
  }

  /**
   * Wraps any input to the linker, be it libraries, linker scripts, linkstamps or linking options.
   */
  // TODO(bazel-team): choose less confusing names for this class and the package-level interface of
  // the same name.
  @Immutable
  public static class LinkerInput implements LinkerInputApi<LtoBackendArtifacts, Artifact> {
    // Identifies which target created the LinkerInput. It doesn't have to be unique between
    // LinkerInputs.
    private final Label owner;
    private final ImmutableList<LibraryToLink> libraries;
    private final ImmutableList<LinkOptions> userLinkFlags;
    private final ImmutableList<Artifact> nonCodeInputs;
    private final ImmutableList<Linkstamp> linkstamps;

    public LinkerInput(
        Label owner,
        ImmutableList<LibraryToLink> libraries,
        ImmutableList<LinkOptions> userLinkFlags,
        ImmutableList<Artifact> nonCodeInputs,
        ImmutableList<Linkstamp> linkstamps) {
      this.owner = owner;
      this.libraries = libraries;
      this.userLinkFlags = userLinkFlags;
      this.nonCodeInputs = nonCodeInputs;
      this.linkstamps = linkstamps;
    }

    @Override
    public boolean isImmutable() {
      return true; // immutable and Starlark-hashable
    }

    @Override
    public Label getStarlarkOwner() throws EvalException {
      if (owner == null) {
        throw Starlark.errorf(
            "Owner is null. This means that some target upstream is of a rule type that uses the"
                + " old API of create_linking_context");
      }
      return owner;
    }

    public Label getOwner() {
      return owner;
    }

    public List<LibraryToLink> getLibraries() {
      return libraries;
    }

    @Override
    public Sequence<LibraryToLink> getStarlarkLibrariesToLink(StarlarkSemantics semantics) {
      return StarlarkList.immutableCopyOf(getLibraries());
    }

    public List<LinkOptions> getUserLinkFlags() {
      return userLinkFlags;
    }

    @Override
    public Sequence<String> getStarlarkUserLinkFlags() {
      return StarlarkList.immutableCopyOf(
          getUserLinkFlags().stream()
              .map(LinkOptions::get)
              .flatMap(Collection::stream)
              .collect(ImmutableList.toImmutableList()));
    }

    public List<Artifact> getNonCodeInputs() {
      return nonCodeInputs;
    }

    @Override
    public Sequence<Artifact> getStarlarkNonCodeInputs() {
      return StarlarkList.immutableCopyOf(getNonCodeInputs());
    }

    public List<Linkstamp> getLinkstamps() {
      return linkstamps;
    }

    @StarlarkMethod(name = "linkstamps", documented = false, structField = true)
    public Sequence<Linkstamp> getLinkstampsForStarlark() {
      return StarlarkList.immutableCopyOf(getLinkstamps());
    }

    @Override
    public void debugPrint(Printer printer, StarlarkThread thread) {
      printer.append("<LinkerInput(owner=");
      if (owner == null) {
        printer.append("[null owner, uses old create_linking_context API]");
      } else {
        owner.debugPrint(printer, thread);
      }
      printer.append(", libraries=[");
      for (LibraryToLink libraryToLink : libraries) {
        libraryToLink.debugPrint(printer, thread);
        printer.append(", ");
      }
      printer.append("], userLinkFlags=[");
      printer.append(Joiner.on(", ").join(userLinkFlags));
      printer.append("], nonCodeInputs=[");
      for (Artifact nonCodeInput : nonCodeInputs) {
        nonCodeInput.debugPrint(printer, thread);
        printer.append(", ");
      }
      // TODO(cparsons): Add debug repesentation of linkstamps.
      printer.append("])>");
    }

    public static Builder builder() {
      return new Builder();
    }

    /** Builder for {@link LinkerInput} */
    public static class Builder {
      private Label owner;
      private final ImmutableList.Builder<LibraryToLink> libraries = ImmutableList.builder();
      private final ImmutableList.Builder<LinkOptions> userLinkFlags = ImmutableList.builder();
      private final ImmutableList.Builder<Artifact> nonCodeInputs = ImmutableList.builder();
      private final ImmutableList.Builder<Linkstamp> linkstamps = ImmutableList.builder();

      @CanIgnoreReturnValue
      public Builder addLibraries(List<LibraryToLink> libraries) {
        this.libraries.addAll(libraries);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addUserLinkFlags(List<LinkOptions> userLinkFlags) {
        this.userLinkFlags.addAll(userLinkFlags);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addLinkstamps(List<Linkstamp> linkstamps) {
        this.linkstamps.addAll(linkstamps);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder addNonCodeInputs(List<Artifact> nonCodeInputs) {
        this.nonCodeInputs.addAll(nonCodeInputs);
        return this;
      }

      @CanIgnoreReturnValue
      public Builder setOwner(Label owner) {
        this.owner = owner;
        return this;
      }

      public LinkerInput build() {
        return new LinkerInput(
            owner,
            libraries.build(),
            userLinkFlags.build(),
            nonCodeInputs.build(),
            linkstamps.build());
      }
    }

    @Override
    public boolean equals(Object otherObject) {
      if (!(otherObject instanceof LinkerInput other)) {
        return false;
      }
      if (this == other) {
        return true;
      }
      return Objects.equal(this.owner, other.owner)
          && this.libraries.equals(other.libraries)
          && this.userLinkFlags.equals(other.userLinkFlags)
          && this.linkstamps.equals(other.linkstamps)
          && this.nonCodeInputs.equals(other.nonCodeInputs);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          libraries.hashCode(),
          userLinkFlags.hashCode(),
          linkstamps.hashCode(),
          nonCodeInputs.hashCode());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("userLinkFlags", userLinkFlags)
          .add("linkstamps", linkstamps)
          .add("libraries", libraries)
          .add("nonCodeInputs", nonCodeInputs)
          .toString();
    }
  }

  private final NestedSet<LinkerInput> linkerInputs;
  @Nullable private final ExtraLinkTimeLibraries extraLinkTimeLibraries;

  @Override
  public void debugPrint(Printer printer, StarlarkThread thread) {
    printer.append("<CcLinkingContext([");
    for (LinkerInput linkerInput : linkerInputs.toList()) {
      linkerInput.debugPrint(printer, thread);
      printer.append(", ");
    }
    printer.append("])>");
  }

  public CcLinkingContext(
      NestedSet<LinkerInput> linkerInputs,
      @Nullable ExtraLinkTimeLibraries extraLinkTimeLibraries) {
    this.linkerInputs = linkerInputs;
    this.extraLinkTimeLibraries = extraLinkTimeLibraries;
  }

  public static CcLinkingContext merge(List<CcLinkingContext> ccLinkingContexts) {
    if (ccLinkingContexts.isEmpty()) {
      return EMPTY;
    }
    Builder mergedCcLinkingContext = CcLinkingContext.builder();
    ExtraLinkTimeLibraries.Builder mergedExtraLinkTimeLibraries = ExtraLinkTimeLibraries.builder();
    for (CcLinkingContext ccLinkingContext : ccLinkingContexts) {
      mergedCcLinkingContext.addTransitiveLinkerInputs(ccLinkingContext.getLinkerInputs());
      if (ccLinkingContext.getExtraLinkTimeLibraries() != null) {
        mergedExtraLinkTimeLibraries.addTransitive(ccLinkingContext.getExtraLinkTimeLibraries());
      }
    }
    mergedCcLinkingContext.setExtraLinkTimeLibraries(mergedExtraLinkTimeLibraries.build());
    return mergedCcLinkingContext.build();
  }

  public List<Artifact> getStaticModeParamsForExecutableLibraries() {
    ImmutableList.Builder<Artifact> libraryListBuilder = ImmutableList.builder();
    for (LibraryToLink libraryToLink : getLibraries().toList()) {
      if (libraryToLink.getStaticLibrary() != null) {
        libraryListBuilder.add(libraryToLink.getStaticLibrary());
      } else if (libraryToLink.getPicStaticLibrary() != null) {
        libraryListBuilder.add(libraryToLink.getPicStaticLibrary());
      } else if (libraryToLink.getInterfaceLibrary() != null) {
        libraryListBuilder.add(libraryToLink.getInterfaceLibrary());
      } else {
        libraryListBuilder.add(libraryToLink.getDynamicLibrary());
      }
    }
    return libraryListBuilder.build();
  }

  public List<Artifact> getStaticModeParamsForDynamicLibraryLibraries() {
    ImmutableList.Builder<Artifact> artifactListBuilder = ImmutableList.builder();
    for (LibraryToLink library : getLibraries().toList()) {
      if (library.getPicStaticLibrary() != null) {
        artifactListBuilder.add(library.getPicStaticLibrary());
      } else if (library.getStaticLibrary() != null) {
        artifactListBuilder.add(library.getStaticLibrary());
      } else if (library.getInterfaceLibrary() != null) {
        artifactListBuilder.add(library.getInterfaceLibrary());
      } else {
        artifactListBuilder.add(library.getDynamicLibrary());
      }
    }
    return artifactListBuilder.build();
  }

  public List<Artifact> getDynamicLibrariesForRuntime(boolean linkingStatically) {
    return LibraryToLink.getDynamicLibrariesForRuntime(linkingStatically, getLibraries().toList());
  }

  public NestedSet<LibraryToLink> getLibraries() {
    NestedSetBuilder<LibraryToLink> libraries = NestedSetBuilder.linkOrder();
    for (LinkerInput linkerInput : linkerInputs.toList()) {
      libraries.addAll(linkerInput.libraries);
    }
    return libraries.build();
  }

  public NestedSet<LinkerInput> getLinkerInputs() {
    return linkerInputs;
  }

  @Override
  public Depset getStarlarkLinkerInputs() {
    return Depset.of(LinkerInput.class, linkerInputs);
  }

  @Override
  public Sequence<String> getStarlarkUserLinkFlags() {
    return StarlarkList.immutableCopyOf(getFlattenedUserLinkFlags());
  }

  @Override
  public Object getStarlarkLibrariesToLink(StarlarkSemantics semantics) {
    // TODO(plf): Flag can be removed already.
    if (semantics.getBool(BuildLanguageOptions.INCOMPATIBLE_DEPSET_FOR_LIBRARIES_TO_LINK_GETTER)) {
      return Depset.of(LibraryToLink.class, getLibraries());
    } else {
      return StarlarkList.immutableCopyOf(getLibraries().toList());
    }
  }

  @Override
  public Depset getStarlarkNonCodeInputs() {
    return Depset.of(Artifact.class, getNonCodeInputs());
  }

  public NestedSet<LinkOptions> getUserLinkFlags() {
    NestedSetBuilder<LinkOptions> userLinkFlags = NestedSetBuilder.linkOrder();
    for (LinkerInput linkerInput : linkerInputs.toList()) {
      userLinkFlags.addAll(linkerInput.getUserLinkFlags());
    }
    return userLinkFlags.build();
  }

  public ImmutableList<String> getFlattenedUserLinkFlags() {
    return getUserLinkFlags().toList().stream()
        .map(LinkOptions::get)
        .flatMap(Collection::stream)
        .collect(ImmutableList.toImmutableList());
  }

  public NestedSet<Linkstamp> getLinkstamps() {
    NestedSetBuilder<Linkstamp> linkstamps = NestedSetBuilder.linkOrder();
    for (LinkerInput linkerInput : linkerInputs.toList()) {
      linkstamps.addAll(linkerInput.getLinkstamps());
    }
    return linkstamps.build();
  }

  @Override
  public Depset getLinkstampsForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return Depset.of(Linkstamp.class, getLinkstamps());
  }

  public NestedSet<Artifact> getNonCodeInputs() {
    NestedSetBuilder<Artifact> nonCodeInputs = NestedSetBuilder.linkOrder();
    for (LinkerInput linkerInput : linkerInputs.toList()) {
      nonCodeInputs.addAll(linkerInput.getNonCodeInputs());
    }
    return nonCodeInputs.build();
  }

  public ExtraLinkTimeLibraries getExtraLinkTimeLibraries() {
    return extraLinkTimeLibraries;
  }

  @Override
  public ExtraLinkTimeLibraries getExtraLinkTimeLibrariesForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getExtraLinkTimeLibraries();
  }

  public static Builder builder() {
    // private to avoid class initialization deadlock between this class and its outer class
    return new Builder();
  }

  /** Builder for {@link CcLinkingContext}. */
  public static class Builder {
    boolean hasDirectLinkerInput;
    LinkerInput.Builder linkerInputBuilder = LinkerInput.builder();
    private final NestedSetBuilder<LinkerInput> linkerInputs = NestedSetBuilder.linkOrder();
    private ExtraLinkTimeLibraries extraLinkTimeLibraries = null;

    @CanIgnoreReturnValue
    public Builder setOwner(Label owner) {
      linkerInputBuilder.setOwner(owner);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addLibraries(List<LibraryToLink> libraries) {
      hasDirectLinkerInput = true;
      linkerInputBuilder.addLibraries(libraries);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addUserLinkFlags(List<LinkOptions> userLinkFlags) {
      hasDirectLinkerInput = true;
      linkerInputBuilder.addUserLinkFlags(userLinkFlags);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addLinkstamps(List<Linkstamp> linkstamps) {
      hasDirectLinkerInput = true;
      linkerInputBuilder.addLinkstamps(linkstamps);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addNonCodeInputs(List<Artifact> nonCodeInputs) {
      hasDirectLinkerInput = true;
      linkerInputBuilder.addNonCodeInputs(nonCodeInputs);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder addTransitiveLinkerInputs(NestedSet<LinkerInput> linkerInputs) {
      this.linkerInputs.addTransitive(linkerInputs);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setExtraLinkTimeLibraries(ExtraLinkTimeLibraries extraLinkTimeLibraries) {
      Preconditions.checkState(this.extraLinkTimeLibraries == null);
      this.extraLinkTimeLibraries = extraLinkTimeLibraries;
      return this;
    }

    public CcLinkingContext build() {
      if (hasDirectLinkerInput) {
        linkerInputs.add(linkerInputBuilder.build());
      }
      return new CcLinkingContext(linkerInputs.build(), extraLinkTimeLibraries);
    }
  }

  @Override
  public boolean equals(Object otherObject) {
    if (!(otherObject instanceof CcLinkingContext other)) {
      return false;
    }
    if (this == other) {
      return true;
    }
    return this.linkerInputs.shallowEquals(other.linkerInputs);
  }

  @Override
  public int hashCode() {
    return linkerInputs.shallowHashCode();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("linkerInputs", linkerInputs).toString();
  }
}
