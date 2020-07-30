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
"""Analysis ranking targets by how many equal configured targets they create.

These are all unnecessary clones that can be eliminated through trimming.
"""
from typing import Mapping
from typing import Tuple
# Do not edit this line. Copybara replaces it with PY2 migration helper.
from dataclasses import dataclass

from tools.ctexplain.types import ConfiguredTarget


# Tuple of config hashes for all CTs corresponding to the same target.
ConfigHashes = Tuple[str, ...]


@dataclass(frozen=True)
class _Clones():
  """Analysis result."""
  # Tuple of all labels in the graph and config hashes of behavior-identical CTs
  # they create Sorted by descending popularity. Hashes are sorted
  # lexicographically.
  #
  # The same label may appear multiple times in the result maybe it may create
  # multiple classes of mutually trimmable configured targets.
  clones: Tuple[Tuple[str, ConfigHashes], ...]


def analyze(
    trimmed_cts: Mapping[ConfiguredTarget, Tuple[ConfiguredTarget, ...]]
    ) -> _Clones:
  """Runs the analysis.

  Args:
    trimmed_cts: The equivalent trimmed cts, where each map entry maps a trimmed
      ct to the untrimmed cts that reduce to it.

  Returns:
    Analysis result as a _Forks.
  """
  clones = []
  for trimmed_ct, untrimmed_cts in trimmed_cts.items():
    hashes = tuple(sorted([ct.config_hash for ct in untrimmed_cts]))
    clones.append((trimmed_ct.label, hashes))
  ranked = sorted(clones, key=lambda k: (0 - len(k[1]), k[0]))
  return _Clones(tuple(ranked))


def report(result: _Clones):
  """Reports analysis results to the user.

  We intentionally make this its own function to make it easy to support other
  output formats (like machine-readable) if we ever want to do that.

  Args:
    result: the analysis result
  """
  print("  Behavior-identical CTs created by each target (this is waste):")
  for label, config_hashes in result.clones:
    if len(config_hashes) == 1:
      # Skip the trivial case that doesn't demonstrate actual bloat.
      continue
    pretty_hashes = ", ".join([hash[0:10] for hash in config_hashes])
    ct_or_cts = "CTs" if len(config_hashes) != 1 else "CT"
    print(f"    {label}: {len(config_hashes)} {ct_or_cts} ({pretty_hashes})")
