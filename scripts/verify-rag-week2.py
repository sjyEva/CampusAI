"""Real HTTP regression for E's week-two interfaces. Does not call a model.

Requires requests. Use a dedicated test course: the negative query assumes its
published corpus does not cover quantum entanglement. Credentials stay in memory.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys
import uuid

import requests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--course', type=int, default=1)
    parser.add_argument('--chapter', type=int, default=102)
    parser.add_argument('--teacher', default='teacher')
    parser.add_argument('--student', default='student')
    parser.add_argument('--other-student', default='student2')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keep', action='store_true', help='Keep three fresh published fixtures after a successful run')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    fixtures = root / 'docs/rag/week2/samples'
    cases = json.loads((fixtures.parent / 'cases.json').read_text(encoding='utf-8'))
    for name, digest in cases['files'].items():
        if hashlib.sha256((fixtures/name).read_bytes()).hexdigest() != digest:
            raise RuntimeError('Fixture hash changed: '+name)
    base = args.base_url.rstrip('/')
    prefix = f'/campusai/courses/{args.course}/rag'
    session = requests.Session()
    tokens = {}
    created = {}
    kept = []
    report = {'startedUtc': dt.datetime.now(dt.timezone.utc).isoformat(), 'baseUrl': base,
              'courseId': args.course, 'chapterId': args.chapter, 'cases': cases,
              'checks': [], 'keptMaterials': kept, 'modelRequests': 0, 'success': False}
    marker = 'week2-' + uuid.uuid4().hex[:10]

    def verify(label, condition, actual, expected):
        report['checks'].append({'check': label, 'passed': bool(condition), 'expected': expected, 'actual': actual})
        if not condition:
            raise AssertionError(label)

    def call(label, method, path, who='teacher', expected=200, **kwargs):
        response = session.request(method, base+path, headers={'Authorization': tokens[who]}, timeout=45, **kwargs)
        try:
            result = response.json()
        except ValueError:
            verify(label, False, {'http': response.status_code, 'contentType': response.headers.get('Content-Type')}, {'code':expected})
        actual = {'http': response.status_code, 'code': result.get('code'), 'message': result.get('msg')}
        verify(label, response.status_code in (200, expected) and result.get('code') == expected, actual, {'code':expected})
        return result.get('data')

    def upload(name, chapter=True):
        data = {'chapterId':str(args.chapter)} if chapter else {}
        with (fixtures/name).open('rb') as handle:
            result = call('upload '+name,'POST',prefix+'/materials',data=data,
                          files={'file':(marker+'-'+name,handle,'application/octet-stream')})
        created[result['id']] = result['version']
        return result

    def publish(material, enabled=True, version=None, expected=200):
        return call('publish' if enabled else 'unpublish','PATCH',prefix+f"/materials/{material['id']}/publication",
                    expected=expected,json={'published':enabled,'expectedVersion':version or material['version']})

    def replace(material, name):
        with (fixtures/name).open('rb') as handle:
            result = call('replace '+name,'PUT',prefix+f"/materials/{material['id']}",
                          params={'version':material['version']},files={'file':(marker+'-'+name,handle,'application/octet-stream')})
        created[result['id']] = result['version']
        return result

    def preview(material, who='student', expected=200, version=None):
        return call('preview','GET',prefix+f"/materials/{material['id']}/preview",who,expected,
                    params={'version':version or material['version'],'limit':1})

    def erase(material):
        call('delete own fixture','DELETE',prefix+f"/materials/{material['id']}",params={'version':material['version']})
        created.pop(material['id'],None)

    try:
        for role, username, envname in [('teacher',args.teacher,'CAMPUSAI_TEACHER_PASSWORD'),
                                        ('student',args.student,'CAMPUSAI_STUDENT_PASSWORD'),
                                        ('other',args.other_student,'CAMPUSAI_STUDENT2_PASSWORD')]:
            password = os.environ.get(envname) or os.environ.get('CAMPUSAI_DEV_PASSWORD','CampusAI-Dev-2026')
            response = session.post(base+'/login',json={'username':username,'password':password},timeout=25)
            result = response.json()
            verify('login '+role,response.status_code==200 and result.get('code')==200 and bool(result.get('token')),
                   {'http':response.status_code,'code':result.get('code')},'authenticated')
            tokens[role] = 'Bearer '+result['token']

        for extension in ('pdf','docx','txt'):
            material = upload('lesson.'+extension)
            verify('parsed '+extension,material['parseStatus']=='READY' and not material['published'] and material['chapterId']==args.chapter,
                   material,{'parseStatus':'READY','published':False,'chapterId':args.chapter})
            preview(material,expected=404)
            publish(material)
            listed=call('chapter list','GET',prefix+'/materials','student',params={'chapterId':args.chapter})
            verify('same UUID in chapter list',any(x['id']==material['id'] for x in listed),[x['id'] for x in listed],material['id'])
            found=call('known question','GET',prefix+'/search','student',params={'q':cases['hitQuestion'],'topK':10})
            matches=[x['source'] for x in found['hits'] if x['source']['materialId']==material['id']]
            verify('search hits uploaded '+extension,bool(matches),[x['materialId'] for x in matches],material['id'])
            source=matches[0]
            expected_location=cases['expectedLocations'][extension]
            verify('real location '+extension,all(source.get(k)==v for k,v in expected_location.items()),
                   {k:source.get(k) for k in expected_location},expected_location)
            original=call('source','GET',prefix+'/sources/'+source['chunkId'],'student')
            verify('source identical',original==source,original,source)
            page=preview(material)
            verify('preview references same material',bool(page['sources']) and page['sources'][0]['materialId']==material['id'],page,material['id'])
            preview(material,who='other',expected=404)
            call('cross-course file','GET',prefix+f"/materials/{material['id']}/file",'other',404)
            negative=call('uncovered question','GET',prefix+'/search','student',params={'q':cases['noEvidenceQuestion']})
            verify('uncovered has no evidence',negative['status']=='no_evidence' and not negative['hits'],negative,'no_evidence with no hits')
            repeat=call('repeat processing','POST',prefix+f"/materials/{material['id']}/reprocess",params={'version':material['version']})
            verify('repeat preserves version/chunks',repeat['version']==material['version'] and repeat['chunkCount']==material['chunkCount']
                   and repeat['published'],repeat,{'version':material['version'],'chunkCount':material['chunkCount'],'published':True})
            publish(material,False)
            preview(material,expected=404)
            call('old source after unpublish','GET',prefix+'/sources/'+source['chunkId'],'student',404)
            absent=call('search after unpublish','GET',prefix+'/search','student',params={'q':cases['hitQuestion'],'topK':10})
            verify('unpublished absent from search',all(x['source']['materialId']!=material['id'] for x in absent['hits']),absent,'uploaded UUID absent')
            updated=replace(material,'replacement.'+extension)
            verify('replacement keeps chapter and increments version',updated['chapterId']==args.chapter and updated['version']==material['version']+1
                   and not updated['published'],updated,{'version':material['version']+1,'chapterId':args.chapter,'published':False})
            publish(updated,version=material['version'],expected=409)
            publish(updated)
            preview(updated,version=material['version'],expected=409)
            call('old source after replacement','GET',prefix+'/sources/'+source['chunkId'],'student',404)
            call('old file version','GET',prefix+f"/materials/{updated['id']}/file",'student',409,params={'version':material['version']})
            newpage=preview(updated)
            newsource=newpage['sources'][0]['chunkId']
            erase(updated)
            preview(updated,expected=404)
            call('source after deletion','GET',prefix+'/sources/'+newsource,'student',404)

        broken=upload('broken.pdf')
        verify('parse failure retains no chunks',broken['parseStatus']=='FAILED' and broken['chunkCount']==0 and bool(broken['errorMessage']),
               broken,{'parseStatus':'FAILED','chunkCount':0,'errorMessage':'nonempty'})
        publish(broken,expected=409)
        retried=call('retry broken file','POST',prefix+f"/materials/{broken['id']}/reprocess",params={'version':broken['version']})
        created[retried['id']]=retried['version']
        verify('failed retry has no usable fragments',retried['parseStatus']=='FAILED' and retried['chunkCount']==0,retried,'FAILED / 0 chunks')
        repaired=replace(retried,'lesson.txt')
        verify('valid replacement repairs failure',repaired['parseStatus']=='READY' and not repaired['published'],repaired,'READY draft')
        publish(repaired);preview(repaired);erase(repaired)
        if args.keep:
            for extension in ('pdf','docx','txt'):
                item=upload('lesson.'+extension);published=publish(item);shown=preview(item)
                kept.append({'material':published,'firstSource':shown['sources'][0]})
        report['success']=True
    except Exception as error:
        report['error']={'type':type(error).__name__,'message':str(error)}
    finally:
        preserve={item['material']['id'] for item in kept} if report['success'] and args.keep else set()
        for material_id,version in list(created.items()):
            if material_id in preserve:continue
            try:erase({'id':material_id,'version':version})
            except Exception as error:
                report['success']=False
                report.setdefault('cleanupErrors',[]).append({'materialId':material_id,'type':type(error).__name__})
        kept[:] = [item for item in kept if item['material']['id'] in created]
        report['remainingCreatedIds']=list(created)
        report['finishedUtc']=dt.datetime.now(dt.timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        session.close()
    print(json.dumps({'success':report['success'],'checks':len(report['checks']),
                      'failedChecks':sum(not x['passed'] for x in report['checks']),
                      'keptMaterials':len(kept),'report':str(args.output)},ensure_ascii=True))
    return 0 if report['success'] else 1


if __name__=='__main__':
    sys.exit(main())
