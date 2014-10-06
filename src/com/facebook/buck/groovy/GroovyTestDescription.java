package com.facebook.buck.groovy;

import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class GroovyTestDescription implements Description<GroovyTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_test");

  private final Optional<Long> testRuleTimeoutMs;

  public GroovyTestDescription(Optional<Long> testRuleTimeoutMs) {
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    return new GroovyTest(
        params,
        pathResolver,
        args.srcs.get(),
        GroovyLibraryDescription.validateResources(
            pathResolver,
            args,
            params.getProjectFilesystem()),
        args.vmArgs.get(),
        args.labels.get(),
        args.contacts.get(),
        validateAndGetSourcesUnderTest(
            args.sourceUnderTest.get(),
            params.getBuildTarget(),
            resolver),
        args.resourcesRoot,
        testRuleTimeoutMs);
  }

  public static ImmutableSet<BuildRule> validateAndGetSourcesUnderTest(
      ImmutableSet<BuildTarget> sourceUnderTestTargets,
      BuildTarget owner,
      BuildRuleResolver resolver) {
    ImmutableSet.Builder<BuildRule> sourceUnderTest = ImmutableSet.builder();
    for (BuildTarget target : sourceUnderTestTargets) {
      BuildRule rule = resolver.getRule(target);
      if (rule == null) {
        throw new HumanReadableException(
            "Specified source under test for %s is not among its dependencies: %s",
            owner,
            target);
      }
      if (!(rule instanceof JavaLibrary)) {
        // In this case, the source under test specified in the build file was not a Java library
        // rule. Since EMMA requires the sources to be in Java, we will throw this exception and
        // not continue with the tests.
        throw new HumanReadableException(
            "Specified source under test for %s is not a Java library: %s (%s).",
            owner,
            rule.getFullyQualifiedName(),
            rule.getType().getName());
      }
      sourceUnderTest.add(rule);
    }
    return sourceUnderTest.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends GroovyLibraryDescription.Arg {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<ImmutableList<String>> vmArgs;
    public Optional<ImmutableSortedSet<Label>> labels;
  }
}
