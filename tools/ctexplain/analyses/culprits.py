# Lint as: python3
# Copyright 2020 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Analysis that shows which flags unnecessarily fork configured targets."""
from typing import Mapping
from typing import Tuple
# Do not edit this line. Copybara replaces it with PY2 migration helper.
from dataclasses import dataclass

from frozendict import frozendict
import tools.ctexplain.lib as lib
from tools.ctexplain.types import ConfiguredTarget


# An options diff is a set of options with each mapped to all values it takes
# across some set of configurations. Each key is a tuple of the option's owning
# class and its name (for example ("PythonOptions", "build_python_zip")).
OptionsDiff = Mapping[Tuple[str, str], Tuple[str, ...]]


@dataclass(frozen=True)
class _Culprits():
  """Analysis result."""
  # Number of configured targets that would disappear in a trimmed graph.
  unnecessary_configured_targets: int
  # Options diffs for every set of mergeable configured targets. If trimmed
  # configured target T corresponds to untrimmed targets U1, U2, and U3, then
  # this has entry (D, L, N) where D = the options diff across U1's, U2's, and
  # U3's configuraitons, L = their common label, and N = the # of unnecessary
  # CTs this diff creates.
  option_diffs: Tuple[Tuple[OptionsDiff, str, int], ...]


def analyze(
    trimmed_cts: Mapping[ConfiguredTarget, Tuple[ConfiguredTarget, ...]]
    ) -> _Culprits:
  """Runs the analysis.

  Args:
    trimmed_cts: Configured targets of an ideally trimmed version of the build
      graph. Each entry maps a trimmed ct to the untrimmed ones that reduce to
      it.

  Returns:
    Analysis result as a _Culprits.
  """
  unnecessary_configured_targets = sum(
      [len(vals) - 1 for vals in trimmed_cts.values() if len(vals) > 1])
  option_diffs = []
  for mergeable_cts in trimmed_cts.values():
    if len(mergeable_cts) > 1:
      option_diffs.append(_get_diffs(mergeable_cts))
  return _Culprits(unnecessary_configured_targets, tuple(option_diffs))


def _get_diffs(
    cts: Tuple[ConfiguredTarget, ...]
    ) -> Tuple[Tuple[OptionsDiff, str, int], ...]:
  """Returns the options diff for a set of untrimmed configured targets.

  Args:
    cts: Untrimmed configured targets that map to the same trimmed variant.

  Returns:
    An Options diff matching a single entry in _Culprits.options_diffs. Values
    are lexicographically sorted.
  """
  # Map each non-user-defined option to the set of every value it takes.
  native_opts = {}
  # User-defined options differ in that they may not appear in the options set
  # at all for their default values. So we add each found value to a list vs. a
  # set. If the list size < # of input CTs, that means at least one of the CTs
  # adopts the default value, which should also be in the results.
  user_defined_opts = {}
  for ct in cts:
    for options_class, options in ct.config.options.items():
      for option, value in options.items():
        option_key = (options_class, option)
        if options_class == "user-defined":
          user_defined_opts.setdefault(option_key, []).append(value)
        else:
          native_opts.setdefault(option_key, set()).add(value)
  diffs = {option: tuple(sorted(vals)) for option, vals in native_opts.items()
           if len(vals) > 1 and option[1] not in lib.trimmable_core_options
          }
  for user_defined_opt, values in user_defined_opts.items():
    all_values = set(values)
    if len(values) < len(cts):
      all_values.add("<default>")
    if len(all_values) > 1:
      diffs[user_defined_opt] = tuple(sorted(all_values))
  return (frozendict(diffs), cts[0].label, len(cts) - 1)


def report(result: _Culprits):
  """Reports analysis results to the user.

  We intentionally make this its own function to make it easy to support other
  output formats (like machine-readable) if we ever want to do that.

  Args:
    result: the analysis result
  """
  # Aggregate option_diffs: if the same diff forks //foo and //bar, it has
  # two entries in option_diffs: ( (diff: (//foo<config1>, //foo<config2>)),
  # (diff: (//bar<config1>, //bar<config2>)) ). This merge's the diff's total
  # impact across the whole graph.
  aggregated_diffs = {}
  for option_diff, label, unnecessary_cts in result.option_diffs:
    aggregated_diff = aggregated_diffs.setdefault(option_diff, [set(), 0])
    assert label not in aggregated_diff[0]
    aggregated_diff[0].add(label)
    aggregated_diff[1] += unnecessary_cts

  print("  Option differences that create " +
        f"{result.unnecessary_configured_targets} unnecessary CTs:")

  # Sort first by # of unnecessary targets, then the diff itself.
  sorted_by_impact = sorted(
      aggregated_diffs.items(), key=lambda x: (0 - x[1][1], x[0]))
  for diff, labels_and_count in sorted_by_impact:
    print(f"  - {labels_and_count[1]} unnecessary CTs:")

    print("      options:")
    for option_name, values in sorted(diff.items()):
      print(f'{" "*8}{option_name[0]}: "{option_name[1]}": {values}')

    print("      labels:")
    curcount = 1
    max_count_len = len(str(len(labels_and_count[0])))
    for label in sorted(labels_and_count[0]):
      list_key = f"{curcount}:".ljust(max_count_len + 2)
      print(f'{" "*8}{list_key}{label}')
      curcount += 1
