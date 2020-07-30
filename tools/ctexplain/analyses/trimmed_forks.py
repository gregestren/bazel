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
"""Analysis ranking targets by how many trimmed configured targets they create.

These are legitimate forks in that their behavior may be different based on
their declared flag requirements. But further build refactoring may be able
to eliminate them.
"""
from typing import Mapping
from typing import Tuple
# Do not edit this line. Copybara replaces it with PY2 migration helper.
from dataclasses import dataclass

from tools.ctexplain.types import ConfiguredTarget


# Tuple of config hashes for all CTs corresponding to the same target.
ConfigHashes = Tuple[str, ...]


@dataclass(frozen=True)
class _TrimmedForks():
  """Analysis result."""
  # Tuple of all labels in the graph and config hashes of CTs they create in an
  # lexicographically.
  trimmed_forks: Tuple[Tuple[str, ConfigHashes], ...]


def analyze(
    trimmed_cts: Mapping[ConfiguredTarget, Tuple[ConfiguredTarget, ...]]
    ) -> _TrimmedForks:
  """Runs the analysis.

  Args:
    trimmed_cts: The equivalent trimmed cts, where each map entry maps a trimmed
      ct to the untrimmed cts that reduce to it.

  Returns:
    Analysis result as a _Forks.
  """
  forks = {}
  for trimmed_ct in trimmed_cts.keys():
    forks.setdefault(trimmed_ct.label, []).append(trimmed_ct.config_hash)
  forks.update((l, tuple(sorted(hashes))) for l, hashes in forks.items())
  ranked = sorted(forks.items(), key=lambda k: (0 - len(k[1]), k[0]))
  return _TrimmedForks(tuple(ranked))


def report(result: _TrimmedForks):
  """Reports analysis results to the user.

  We intentionally make this its own function to make it easy to support other
  output formats (like machine-readable) if we ever want to do that.

  Args:
    result: the analysis result
  """
  print(" CTs created by each target in an optimally trimmed graph:")
  for label, config_hashes in result.trimmed_forks:
    ct_or_cts = "CTs" if len(config_hashes) != 1 else "CT"
    # TODO(gregce): denote null, host, arbitrary hashes without being confusing.
    print(f"    {label}: {len(config_hashes)} {ct_or_cts}")
