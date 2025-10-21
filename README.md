## What does this plugin allow to do?

You can run script on a merge node (a node that merges shard results). For example:

```yaml
ext:
  merge_script:
    field: model_id
    window_size: 10000
    merge_script:
      fields: [is_turbo]
      script:
        source: |
          SearchHit[] mixedHits = new SearchHit[hits.length];
          List commonHits = new ArrayList();
          List turboHits = new ArrayList();
          for (SearchHit hit : hits) {
              def isTurbo = hit.field("is_turbo").booleanValueOrDefault(false);
              if (isTurbo) {
                  turboHits.add(hit);
              } else {
                  commonHits.add(hit);
              }
          }
          List hitsIterators = new ArrayList();
          hitsIterators.add(commonHits.iterator());
          hitsIterators.add(turboHits.iterator());
          int i = 0;
          while (!hitsIterators.isEmpty()) {
              ListIterator hitsIteratorsIter = hitsIterators.listIterator();
              while (hitsIteratorsIter.hasNext()) {
                  Iterator hitsIter = hitsIteratorsIter.next();
                  if (hitsIter.hasNext()) {
                      mixedHits[i++] = hitsIter.next();
                  } else {
                      hitsIteratorsIter.remove();
                  }

              }
          }
          return mixedHits;
```

This query fetches up to 10_000 hits from every shard, split them into 2 groups
and mix them ony by one.
