// Code generated by bavard DO NOT EDIT

package wizard
import (
	"strconv"

	"github.com/consensys/gnark/frontend"
)

func (i *QueryInclusion) WithTags(tags ...string) *QueryInclusion {
	i.metadata.tags = append(i.metadata.tags, tags...)
	return i
}

func (i *QueryInclusion) WithName(name string) *QueryInclusion {
	i.metadata.name = name
	return i
}

func (i *QueryInclusion) WithDoc(doc string) *QueryInclusion {
	i.metadata.doc = doc
	return i
}

func (i *QueryInclusion) Tags() []string {
	return i.metadata.tags
}

func (i *QueryInclusion) ListTags() []string {
	return i.metadata.listTags()
}

func (i *QueryInclusion) String() string {
	return i.metadata.scope.getFullScope() + "/" + i.metadata.nameOrDefault(i) + "/" + strconv.Itoa(int(i.metadata.id))
}

func (i *QueryInclusion) Explain() string {
	return i.metadata.explain(i)
}
func (i *QueryInclusion) id() id {
	return i.metadata.id
}
// ComputeResult does not return any result for [QueryInclusion] because Global
// constraints do not return results as they are purely constraints that must
// be satisfied.
func (i QueryInclusion) ComputeResult(run Runtime) QueryResult {
	return &QueryResNone{}
}

// ComputeResult does not return any result for [QueryInclusion] because Global
// constraints do not return results.
func (i QueryInclusion) ComputeResultGnark(_ frontend.API, run GnarkRuntime) QueryResultGnark {
	return &QueryResNoneGnark{}
}