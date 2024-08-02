// Code generated by bavard DO NOT EDIT

package wizard
import (
	"strconv"

	"github.com/consensys/gnark/frontend"
)

func (i *QueryPermutation) WithTags(tags ...string) *QueryPermutation {
	i.metadata.tags = append(i.metadata.tags, tags...)
	return i
}

func (i *QueryPermutation) WithName(name string) *QueryPermutation {
	i.metadata.name = name
	return i
}

func (i *QueryPermutation) WithDoc(doc string) *QueryPermutation {
	i.metadata.doc = doc
	return i
}

func (i *QueryPermutation) Tags() []string {
	return i.metadata.tags
}

func (i *QueryPermutation) ListTags() []string {
	return i.metadata.listTags()
}

func (i *QueryPermutation) String() string {
	return i.metadata.scope.getFullScope() + "/" + i.metadata.nameOrDefault(i) + "/" + strconv.Itoa(int(i.metadata.id))
}

func (i *QueryPermutation) Explain() string {
	return i.metadata.explain(i)
}
func (i *QueryPermutation) id() id {
	return i.metadata.id
}
// ComputeResult does not return any result for [QueryPermutation] because Global
// constraints do not return results as they are purely constraints that must
// be satisfied.
func (i QueryPermutation) ComputeResult(run Runtime) QueryResult {
	return &QueryResNone{}
}

// ComputeResult does not return any result for [QueryPermutation] because Global
// constraints do not return results.
func (i QueryPermutation) ComputeResultGnark(_ frontend.API, run GnarkRuntime) QueryResultGnark {
	return &QueryResNoneGnark{}
}