package com.library.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.library.entity.*;
import com.library.common.PageResult;
import java.util.List;
public interface BookshelfService {
 PageResult<Bookshelf> getPage(int page,int size,String keyword,Long deptId);
 Bookshelf getById(Long id);
 Bookshelf create(JsonNode body,Long actor); Bookshelf update(JsonNode body,Long actor); void delete(Long id,JsonNode body,Long actor);
 List<BookshelfStorey> getStoreysByBookshelfId(Long id);
 BookshelfStorey createStorey(JsonNode body,Long actor); BookshelfStorey updateStorey(JsonNode body,Long actor);void deleteStorey(Long id,JsonNode body,Long actor);
 PageResult<BookLocation> getLocations(int page,int size,Long storeyId,String status);
 BookLocation createLocation(JsonNode body,Long actor); BookLocation updateLocation(JsonNode body,Long actor);void deleteLocation(Long id,JsonNode body,Long actor);
 void assignBookToLocation(Long id,Long book,JsonNode body,Long actor);void clearLocation(Long id,JsonNode body,Long actor);
}